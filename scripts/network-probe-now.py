#!/usr/bin/env python3
"""Run the configured network probes once from ZJ without restarting the DaemonSet."""
import base64
import fcntl
import json
import io
import math
import os
import re
import subprocess
import sys
import time
import urllib.request

NAMESPACE = os.environ.get('TOPIC4_NAMESPACE', 'topic4-1')


def kubectl(*args, timeout=30):
    return subprocess.check_output(
        ['kubectl', '-n', NAMESPACE] + list(args), timeout=timeout, universal_newlines=True)


def post(url, token, payload):
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={
        'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.load(response)
    if result.get('code') not in (0, 200):
        raise RuntimeError('Central service rejected request')
    return result


def parse_latency(output):
    match = re.search(r'min/avg/max[^=]*=\s*[^/]+/([^/]+)/', output)
    value = float(match.group(1)) if match else -1
    return value if math.isfinite(value) and value >= 0 else None


def execute(pod, *args, timeout=30):
    result = subprocess.run(
        ['kubectl', '-n', NAMESPACE, 'exec', pod, '-c', 'network-probe', '--'] + list(args),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, universal_newlines=True)
    return result.returncode, result.stdout


def bandwidth(pod, ip, seconds, reverse=False):
    command = ['iperf3', '-c', ip, '-t', str(seconds), '-J']
    if reverse:
        command.append('-R')
    for attempt in range(4):
        code, output = execute(pod, *command, timeout=seconds + 25)
        try:
            result = json.loads(output)
        except ValueError:
            return None
        if 'server is busy running a test' in result.get('error', '') and attempt < 3:
            time.sleep(5 * (attempt + 1))
            continue
        if code or result.get('error'):
            return None
        rate = result.get('end', {}).get('sum_received', {}).get('bits_per_second')
        return int(rate) if isinstance(rate, (int, float)) and math.isfinite(rate) and 1 <= rate < 2**63 else None


def main():
    # Keep the lock file in place: unlinking it would allow another inode to be locked.
    with open('/tmp/topic4-network-probe-now-' + NAMESPACE + '.lock', 'a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('已有手动探测正在运行，请等待完成。')
        run()


def run():
    ds = json.loads(kubectl('get', 'ds', 'network-probe', '-o', 'json'))
    container = next(c for c in ds['spec']['template']['spec']['containers'] if c['name'] == 'network-probe')
    env = {e['name']: e.get('value', '') for e in container['env']}
    token = env.get('CENTRAL_AUTH_TOKEN', '')
    if not token:
        ref = next(e for e in container['env'] if e['name'] == 'CENTRAL_AUTH_TOKEN')['valueFrom']['secretKeyRef']
        secret = json.loads(kubectl('get', 'secret', ref['name'], '-o', 'json'))
        token = base64.b64decode(secret['data'][ref['key']]).decode()
    pods = json.loads(kubectl('get', 'pods', '-l', 'app=network-probe', '-o', 'json'))['items']
    ready = {p['spec']['nodeName']: p['metadata']['name'] for p in pods
             if not p['metadata'].get('deletionTimestamp') and
             any(c['type'] == 'Ready' and c['status'] == 'True' for c in p['status'].get('conditions', []))}
    nodes = json.loads(kubectl('get', 'nodes', '-o', 'json'))['items']
    ips = {n['metadata']['name']: next((a['address'] for a in n['status']['addresses']
           if a['type'] == 'InternalIP'), None) for n in nodes}
    ips.update(dict(e.strip().split('=', 1) for e in env.get('PROBE_NODE_LAN_IP_OVERRIDES', '').split(',') if '=' in e))
    targets = []
    for edge in env.get('PROBE_TARGET_EDGES', '').split(','):
        if not edge.strip():
            continue
        source, target = sorted(edge.strip().split('/'))
        targets.append((source, target, source, ips.get(target)))
    for edge in env.get('PROBE_EXTERNAL_EDGES', '').split(','):
        if not edge.strip():
            continue
        remote, source = edge.strip().split('/')
        target, ip = remote.rsplit('@', 1)
        targets.append((source, target, env['PROBE_EXTERNAL_RUNNER_NODE'], ip))
    targets = list(dict.fromkeys(targets))
    if not targets:
        raise RuntimeError('线上未配置探测链路。')
    seconds = int(env.get('PROBE_TRANSFER_SECONDS', '2'))
    success = skipped = failed = 0
    print('开始手动探测 {} 条配置链路；自动探测配置保持不变。'.format(len(targets)), flush=True)
    for source, target, runner, ip in targets:
        label = '{} ↔ {}'.format(source, target)
        try:
            if runner not in ready or not ip:
                raise RuntimeError('探测 Pod 或目标 IP 不可用')
            decision = post(env['CENTRAL_PROBE_DECISION_URL'], token,
                            {'sourceNode': source, 'targetNode': target})['data']
            if not decision.get('permitted'):
                skipped += 1
                print('跳过 {}：{}'.format(label, decision.get('reason', 'NOT_PERMITTED')), flush=True)
                continue
            pod = ready[runner]
            code, output = execute(pod, 'env', 'LC_ALL=C', 'ping', '-n', '-c', '4', ip, timeout=20)
            latency = parse_latency(output) if code == 0 else None
            forward = bandwidth(pod, ip, seconds)
            reverse = bandwidth(pod, ip, seconds, reverse=True)
            valid_rates = [rate for rate in (forward, reverse) if rate is not None]
            rate = min(valid_rates) if valid_rates else None
            report = {'sourceNode': source, 'targetNode': target, 'latencyMs': latency,
                      'bandwidthBps': rate, 'measurementTime': int(time.time() * 1000)}
            post(env['CENTRAL_METRICS_URL'], token, [report])
            if latency is None or rate is None:
                failed += 1
                print('失败 {}：测量不完整，已上报不可用状态。'.format(label), flush=True)
            else:
                success += 1
                print('成功 {}：RTT {:.3f} ms，带宽 {:.2f} Mbps；已写回系统。'.format(
                    label, latency, rate / 1e6), flush=True)
        except Exception as error:
            failed += 1
            # Do not print request objects or credentials.
            print('失败 {}：{}'.format(label, type(error).__name__), flush=True)
    print('完成：成功 {}，跳过 {}，失败 {}。'.format(success, skipped, failed), flush=True)
    if failed or skipped:
        sys.exit(1)


if __name__ == '__main__':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', line_buffering=True)
    try:
        main()
    except Exception as error:
        print('手动探测未完成：{}'.format(str(error) if isinstance(error, RuntimeError) else type(error).__name__), file=sys.stderr)
        sys.exit(1)

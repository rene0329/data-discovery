#!/usr/bin/env python3
"""Run the configured network probes once from ZJ without restarting the DaemonSet."""
import base64
import datetime
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



def print_summary(results, elapsed):
    print('\n测量汇总（耗时 {:.1f} 秒）'.format(elapsed), flush=True)
    print('链路 | 平均往返时延 (ms) | 丢包率 | 写回结果', flush=True)
    for result in results:
        latency = result['latency']
        print('{} | {} | {} | {}'.format(
            result['label'], '{:.3f}'.format(latency) if latency is not None else '未测得',
            result['loss'], result['status']), flush=True)
    counts = {state: sum(r['outcome'] == state for r in results)
              for state in ('success', 'skipped', 'failed')}
    print('完成：共 {} 条，成功 {}，跳过 {}，失败 {}。'.format(
        len(results), counts['success'], counts['skipped'], counts['failed']), flush=True)


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
    started = time.monotonic()
    results = []
    print('开始手动探测 {} 条配置链路；自动探测配置保持不变。'.format(len(targets)), flush=True)
    print('延迟方法：ICMP Echo 往返时延；每条链路发送 10 个报文，间隔 1 秒，单次等待 2 秒，总限时 20 秒。', flush=True)
    print('逐次回显来自目标节点；最终取成功响应的平均 RTT 写回系统，丢包率单独显示。', flush=True)
    print('这是一次短时采样，不代表长期延迟分布；每日自动探测的采样参数保持不变。', flush=True)
    for index, (source, target, runner, ip) in enumerate(targets, 1):
        label = '{} ↔ {}'.format(source, target)
        result = {'label': label, 'latency': None, 'loss': '未知',
                  'status': '未写回', 'outcome': 'failed'}
        results.append(result)
        try:
            if runner not in ready or not ip:
                raise RuntimeError('探测 Pod 或目标 IP 不可用')
            decision = post(env['CENTRAL_PROBE_DECISION_URL'], token,
                            {'sourceNode': source, 'targetNode': target})['data']
            if not decision.get('permitted'):
                skipped += 1
                result.update(outcome='skipped', status='跳过：' + decision.get('reason', 'NOT_PERMITTED'))
                print('跳过 {}：{}'.format(label, decision.get('reason', 'NOT_PERMITTED')), flush=True)
                continue
            pod = ready[runner]
            print('\n[{}/{}] {} → {}；实际执行节点 {}，目标 IP {}，Pod {}'.format(
                index, len(targets), source, target, runner, ip, pod), flush=True)
            print('开始时间：' + datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S %z'), flush=True)
            print('执行：ping -n -c 10 -i 1 -W 2 -w 20 ' + ip, flush=True)
            print('采样中，最多等待 20 秒；结束后显示原始逐次响应和统计：', flush=True)
            code, output = execute(pod, 'env', 'LC_ALL=C', 'ping', '-n', '-c', '10',
                                   '-i', '1', '-W', '2', '-w', '20', ip, timeout=30)
            print(output.rstrip() or '未收到 ping 输出。', flush=True)
            # iputils can exit 1 at the deadline even when some replies arrived.
            # Only a valid RTT summary permits using those successful replies.
            latency = parse_latency(output) if code in (0, 1) else None
            loss = re.search(r'([0-9.]+)% packet loss', output)
            result.update(latency=latency, loss=loss.group(1) + '%' if loss else '未知')
            if latency is not None:
                print('延迟结果：平均往返时延 {:.3f} ms（仅统计成功响应，未响应报文计入丢包）。'.format(latency), flush=True)
            else:
                print('延迟结果：未获得有效 RTT，不能记为 0 ms。', flush=True)
            print('正在完成其余链路指标检查并提交系统…', flush=True)
            forward = bandwidth(pod, ip, seconds)
            reverse = bandwidth(pod, ip, seconds, reverse=True)
            valid_rates = [rate for rate in (forward, reverse) if rate is not None]
            rate = min(valid_rates) if valid_rates else None
            report = {'sourceNode': source, 'targetNode': target, 'latencyMs': latency,
                      'bandwidthBps': rate, 'measurementTime': int(time.time() * 1000)}
            post(env['CENTRAL_METRICS_URL'], token, [report])
            if latency is None or rate is None:
                failed += 1
                result['status'] = '已上报不可用状态'
                print('失败 {}：测量不完整，已上报不可用状态。'.format(label), flush=True)
            else:
                success += 1
                result.update(outcome='success', status='已写回')
                print('成功 {}：平均往返时延 {:.3f} ms；已写回系统。'.format(
                    label, latency), flush=True)
        except Exception as error:
            failed += 1
            result['status'] = '未完成：' + type(error).__name__
            # Do not print request objects or credentials.
            print('失败 {}：{}'.format(label, type(error).__name__), flush=True)
    print_summary(results, time.monotonic() - started)
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

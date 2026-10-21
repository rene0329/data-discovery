#!/usr/bin/env python3
"""TC-4.2 read-only queries with automatic session login; standard library only."""
import argparse
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit, quote
from urllib.request import Request, build_opener, HTTPRedirectHandler


class NoRedirect(HTTPRedirectHandler):
    # Never forward the bearer token to a redirected host.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def positive(value):
    if not re.fullmatch(r'[0-9]+', value) or int(value) < 1:
        raise argparse.ArgumentTypeError('编号和分页参数必须是正整数')
    return int(value)


def parser():
    p = argparse.ArgumentParser(description='TC-4.2 前五项测试的前端同源原始接口查询（只读）')
    p.add_argument('--base-url', default=os.environ.get('TOPIC4_API_URL', 'http://10.15.16.40:30482'))
    p.add_argument('--raw', action='store_true', help='原样输出响应字节；默认仅对 JSON 缩进，不修改字段')
    p.add_argument('--timeout', type=positive, default=60)
    p.add_argument('--no-auto-login', action='store_true', help='禁用自动登录，要求提供 TOPIC4_TOKEN')
    s = p.add_subparsers(dest='command')
    s.required = True  # Python 3.6 on the ZJ build host.
    for name in ('datasets', 'nodes', 'tasks', 'plans'):
        c = s.add_parser(name, help='分页查询，单次返回一页')
        c.add_argument('--page', type=positive, default=1)
        c.add_argument('--page-size', type=positive, default=20)
        c.add_argument('--query', default='')
    s.add_parser('topology', help='网络拓扑节点及连接信息')
    s.add_parser('me', help='当前认证用户')
    for name in ('task', 'tc1', 'tc2', 'speedup', 'tc5'):
        c = s.add_parser(name, help='完整任务及执行事件；多个任务逐个输出')
        c.add_argument('ids', nargs='+', type=positive)
    for name in ('dataset', 'heat', 'tc3', 'replicas'):
        c = s.add_parser(name, help='数据集详情（含温度）或副本信息')
        c.add_argument('ids', nargs='+', type=positive)
    c = s.add_parser('plan', help='存储调度计划及分配记录')
    c.add_argument('id', type=positive)
    s.add_parser('policy', help='温度及存储策略参数')
    for name in ('access', 'tc4'):
        c = s.add_parser(name, help='当前账号的数据访问权限与短期授权状态')
    c = s.add_parser('audit', help='访问授权审计记录；管理员权限')
    for arg in ('principal', 'run-id', 'request-id'):
        c.add_argument('--' + arg)
    c.add_argument('--decision', choices=['ALLOWED', 'DENIED'])
    c.add_argument('--limit', type=positive, default=100)
    c = s.add_parser('reads', help='数据集实际读取事件')
    c.add_argument('id', type=positive)
    c.add_argument('--limit', type=positive, default=100)
    c = s.add_parser('grants', help='短期授权发放日志；管理员权限')
    c.add_argument('--limit', type=positive, default=500)
    c = s.add_parser('comparison', help='按测试运行标识对比分开提交的两种模式')
    c.add_argument('run_id')
    c.add_argument('--round', type=positive, default=1)
    return p


def routes(a):
    cmd = a.command
    if cmd in ('datasets', 'nodes', 'tasks', 'plans'):
        path = {'datasets': '/api/v1/datasets', 'nodes': '/api/v1/nodes',
                'tasks': '/common/taskList', 'plans': '/api/v1/scheduling/plans'}[cmd]
        return [(path, {'page': a.page, 'pageSize': a.page_size, 'query': a.query})]
    if cmd in ('task', 'tc1', 'tc2', 'speedup', 'tc5'):
        return [('/api/v1/tasks/' + str(i), {}) for i in a.ids]
    if cmd in ('dataset', 'heat', 'tc3', 'replicas'):
        return [('/api/v1/datasets/' + str(i) + ('/replicas' if cmd == 'replicas' else ''), {}) for i in a.ids]
    fixed = {'topology': '/common/networkConstruction', 'me': '/api/v1/auth/me',
             'policy': '/api/v1/datasets/storage-policy',
             'access': '/api/v1/security/dataset-access', 'tc4': '/api/v1/security/dataset-access'}
    if cmd in fixed:
        return [(fixed[cmd], {})]
    if cmd == 'plan':
        return [('/api/v1/scheduling/plans/' + str(a.id), {})]
    if cmd == 'audit':
        return [('/api/v1/security/access/events', {k: v for k, v in {
            'principal': a.principal, 'runId': a.run_id, 'requestId': a.request_id,
            'decision': a.decision, 'limit': a.limit}.items() if v is not None})]
    if cmd == 'reads':
        return [('/api/v1/dataset-access-events', {'datasetId': a.id, 'limit': a.limit})]
    if cmd == 'grants':
        return [('/api/v1/security/dataset-access/grants', {'limit': a.limit})]
    if cmd == 'comparison':
        return [('/api/v1/tasks/runs/' + quote(a.run_id, safe='') + '/comparison', {'round': a.round})]
    raise ValueError('未知命令')


def login(opener, base, timeout):
    """Obtain a fresh session from the deployment's configured login endpoint.

    Keep the token in this process only; authentication responses must never be
    mixed into the business data or request logs.
    """
    req = Request(base + '/api/v1/auth/direct-login', data=b'',
                  headers={'Accept': 'application/json'}, method='POST')
    try:
        with opener.open(req, timeout=timeout) as response:
            payload = json.loads(response.read())
    except HTTPError as exc:
        raise RuntimeError('自动登录失败：HTTP {}；请确认直接登录接口已启用，或提供 TOPIC4_TOKEN'.format(exc.code))
    except (URLError, TimeoutError, OSError, ValueError, UnicodeError):
        raise RuntimeError('自动登录失败：网络异常或响应不是有效 JSON')
    data = payload.get('data') if isinstance(payload, dict) else None
    token = data.get('token') if isinstance(data, dict) else None
    if (not isinstance(payload, dict) or payload.get('code') != 0 or not isinstance(token, str)
            or not token.strip() or '\n' in token or '\r' in token):
        raise RuntimeError('自动登录失败：接口未返回有效令牌')
    return token.strip()


def main(argv=None):
    p = parser()
    a = p.parse_args(argv)
    base = a.base_url.rstrip('/')
    url = urlsplit(base)
    if url.scheme not in ('http', 'https') or not url.netloc or url.username or url.password or url.query or url.fragment:
        p.error('--base-url 必须为无账号密码、查询参数或片段的 HTTP(S) 地址')
    token = os.environ.get('TOPIC4_TOKEN', '').strip()
    if '\n' in token or '\r' in token:
        p.error('TOPIC4_TOKEN 不能包含换行符')
    opener = build_opener(NoRedirect())
    if not token:
        if a.no_auto_login:
            p.error('已禁用自动登录，请通过 TOPIC4_TOKEN 提供当前用户的 Bearer 令牌')
        try:
            token = login(opener, base, a.timeout)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return 1
    failed = False
    for path, params in routes(a):
        target = base + path + ('?' + urlencode(params) if params else '')
        print('GET ' + target, file=sys.stderr, flush=True)
        req = Request(target, headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/json'})
        status = 0
        try:
            with opener.open(req, timeout=a.timeout) as response:
                status, body = response.status, response.read()
        except HTTPError as exc:
            status, body = exc.code, exc.read()
        except (URLError, TimeoutError, OSError) as exc:
            print('请求失败：' + str(exc), file=sys.stderr)
            failed = True
            continue
        try:
            payload = json.loads(body)
            valid = isinstance(payload, dict) and payload.get('code') == 0
        except (ValueError, UnicodeError):
            payload, valid = None, False
        if a.raw:
            sys.stdout.buffer.write(body)
            sys.stdout.buffer.flush()
        else:
            print(json.dumps(payload, ensure_ascii=False, indent=2) if payload is not None else body.decode('utf-8', errors='replace'), flush=True)
        if not 200 <= status < 300 or not valid:
            print('查询未成功：HTTP {}；原始响应已输出'.format(status), file=sys.stderr)
            failed = True
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

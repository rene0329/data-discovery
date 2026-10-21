"""Local HTTP contract checks, no cluster or credentials required."""
import json
import os
from pathlib import Path
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

SCRIPT = Path(__file__).resolve().parents[1] / 'tc42-api.sh'


def task_fixture(task_id):
    data = {'task': {'taskId': task_id, 'executionMode': 'COMPARISON',
                     'datasetIdsJson': '[7]', 'status': '已完成',
                     'startedAt': '2026-10-18 11:38', 'finishedAt': '2026-10-18 11:39',
                     't1': 20.663, 't2': 32.941, 'rating': 1.594,
                     'executionEvidenceComplete': True, 'futureField': 'hide me'}, 'events': []}
    for mode, node, ms in [('CENTRALIZED', 'master-40', 32941), ('IN_PLACE', 'master-215', 20663)]:
        base = {'datasetId': 7, 'executionMode': mode, 'nodeName': node,
                'jobName': mode + '-job', 'podName': mode + '-pod', 'attempt': 1,
                'occurredAt': '2026-10-18 11:39'}
        for kind in ['PLACEMENT_SELECTED', 'DATA_PREPARATION_STARTED', 'DATA_PREPARATION_READY',
                     'COMPUTE_STARTED', 'COMPUTE_COMPLETED']:
            event = dict(base, eventId=len(data['events'])+1, eventType=kind)
            if kind == 'PLACEMENT_SELECTED':
                event['detailsJson'] = json.dumps({'sourceNode': 'cluster-hz-1', 'targetNode': node})
            if kind == 'DATA_PREPARATION_READY':
                event.update(bytesProcessed=1106641002, durationMs=ms, checksumSha256='a'*64)
            data['events'].append(event)
    if task_id == 406:
        data['task']['t1'] = 0
    elif task_id == 407:
        data['events'] = [e for e in data['events'] if e['executionMode'] != 'IN_PLACE']
    elif task_id == 408:
        data['events'][7]['checksumSha256'] = 'b'*64
    elif task_id == 409:
        data['events'].append({'eventId': 99, 'datasetId': 7, 'executionMode': 'IN_PLACE',
                               'eventType': 'DATA_PREPARATION_STARTED', 'jobName': 'new-job',
                               'podName': 'new-pod', 'attempt': 2, 'nodeName': 'master-215'})
    elif task_id == 410:
        data['task']['t2'] += 1
    return data


class Handler(BaseHTTPRequestHandler):
    requests = []
    logins = []
    login_failure = False

    def log_message(self, *args):
        pass

    def do_POST(self):
        self.logins.append(self.path)
        status = 403 if self.login_failure else 200
        self.send_response(status)
        self.end_headers()
        self.wfile.write(b'{"code":0,"data":{"token":"automatic-session-token"}}')

    def do_GET(self):
        self.requests.append((self.path, self.headers.get('Authorization')))
        if self.path.endswith('/401'):
            status, body = 401, b'{"code":401,"msg":"denied"}'
        elif self.path.endswith('/402'):
            status, body = 200, b'{"code":7,"msg":"business error"}'
        elif self.path.endswith('/403'):
            status, body = 200, b'<html>wrong endpoint</html>'
        elif self.path.endswith('/404'):
            status, body = 200, '{"code":0,"data":{"status":"已完成"}}'.encode('utf-8')
        elif self.path.split('?')[0] == '/api/v1/datasets':
            status, body = 200, json.dumps({'code': 0, 'data': {'list': [
                {'datasetId': 7, 'name': 'Tiny ImageNet', 'authoritativeSha256': 'a'*64,
                 'authoritativeSizeBytes': 1106641002}], 'total': 1}}).encode('utf-8')
        elif self.path.startswith('/api/v1/tasks/') and self.path.rsplit('/', 1)[-1] in [str(i) for i in range(405, 411)]:
            status, body = 200, json.dumps({'code': 0, 'data': task_fixture(int(self.path.rsplit('/', 1)[-1])),
                                         'traceId': 'hidden-trace'}).encode('utf-8')
        else:
            status, body = 200, b'{ "code": 0, "data": {"t1": 20.663, "t2": 32.941, "events": []} }'
        self.send_response(status)
        self.end_headers()
        self.wfile.write(body)


class Contract(unittest.TestCase):
    def setUp(self):
        Handler.login_failure = False

    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def run_cli(self, *args, token='test-token'):
        env = dict(os.environ, TOPIC4_TOKEN=token, LC_ALL='C',
                   PYTHONCOERCECLOCALE='0', PYTHONUTF8='0')
        return subprocess.run([str(SCRIPT), '--base-url',
                               'http://127.0.0.1:' + str(self.server.server_port), *args],
                              env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_chinese_output_in_ascii_locale(self):
        result = self.run_cli('task', '404')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(json.loads(result.stdout.decode('utf-8'))['data']['status'], '已完成')

    def test_raw_is_unchanged_and_authenticated(self):
        result = self.run_cli('--raw', 'task', '233')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b'{ "code": 0, "data": {"t1": 20.663, "t2": 32.941, "events": []} }')
        self.assertEqual(Handler.requests[-1], ('/api/v1/tasks/233', 'Bearer test-token'))
        self.assertNotIn(b'test-token', result.stderr)

    def test_pretty_keeps_fields_and_timing(self):
        result = self.run_cli('tc5', '233')
        self.assertEqual(json.loads(result.stdout)['data']['t1'], 20.663)
        self.assertEqual(json.loads(result.stdout)['data']['t2'], 32.941)

    def test_human_flag_before_or_after_command(self):
        for args in (('-h', 'tc5', '405'), ('tc5', '405', '-h'),
                     ('tc5', '--human-readable', '405')):
            result = self.run_cli(*args)
            self.assertEqual(result.returncode, 0)
            view = result.stdout.decode('utf-8')
            for value in ('TC-4.2.5 数据移动加速比', '32.941', '20.663', '1.594',
                          '达到要求', 'Tiny ImageNet', 'cluster-hz-1', 'master-40', 'master-215'):
                self.assertIn(value, view)
            for hidden in ('futureField', 'hidden-trace', 'eventId', 'podName', '未提供', 'HTTP 200'):
                self.assertNotIn(hidden, view)

    def test_human_task_preserves_key_evidence_only(self):
        view = self.run_cli('task', '405', '-h').stdout.decode('utf-8')
        for value in ('计划节点', '实际节点', '输入校验', '与登记值一致', '1.03 GiB', '传输起止', '计算起止'):
            self.assertIn(value, view)
        self.assertNotIn('futureField', view)

    def test_incomplete_or_invalid_evidence_cannot_pass(self):
        reasons = {406: '分布式计时不大于零', 407: '逐数据集执行或计时缺失',
                   408: '两种模式输入不一致', 409: '逐数据集执行或计时缺失',
                   410: '累计计时与任务记录不一致'}
        for task, reason in reasons.items():
            result = self.run_cli('tc5', str(task), '-h')
            self.assertEqual(result.returncode, 0)  # A successful query is distinct from test acceptance.
            view = result.stdout.decode('utf-8')
            self.assertIn('不纳入判定', view)
            self.assertIn(reason, view)
            self.assertNotIn('达到要求', view)

    def test_batch_speedup_has_one_header_and_merges_routes(self):
        view = self.run_cli('tc5', '405', '405', '-h').stdout.decode('utf-8')
        self.assertEqual(view.count('TC-4.2.5 数据移动加速比'), 1)
        self.assertEqual(view.count('cluster-hz-1'), 1)
        self.assertNotIn('GET http', view)

    def test_human_and_raw_conflict_before_login(self):
        before = len(Handler.logins)
        self.assertEqual(self.run_cli('--raw', 'task', '233', '-h', token='').returncode, 2)
        self.assertEqual(len(Handler.logins), before)

    def test_help_uses_long_option_without_network(self):
        before = len(Handler.logins)
        for args in (('--help',), ('tc5', '--help')):
            result = self.run_cli(*args, token='')
            self.assertEqual(result.returncode, 0)
            self.assertIn(b'--human-readable', result.stdout)
        self.assertEqual(len(Handler.logins), before)

    def test_human_error_still_fails_and_keeps_reason(self):
        result = self.run_cli('task', '401', '-h')
        self.assertEqual(result.returncode, 1)
        self.assertIn('查询失败：', result.stdout.decode('utf-8'))
        self.assertIn('denied', result.stdout.decode('utf-8'))

    def test_errors_are_visible_and_nonzero(self):
        for task in ('401', '402', '403'):
            result = self.run_cli('task', task)
            self.assertEqual(result.returncode, 1)
            self.assertTrue(result.stdout)

    def test_invalid_id_and_disabled_login_never_request(self):
        before = len(Handler.requests)
        for args, token in [(('task', '1;DROP'), 'test'), (('--no-auto-login', 'task', '1'), '')]:
            self.assertEqual(self.run_cli(*args, token=token).returncode, 2)
        self.assertEqual(len(Handler.requests), before)

    def test_automatic_login_without_environment_token(self):
        before = len(Handler.logins)
        result = self.run_cli('task', '233', '234', token='')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(len(Handler.logins), before + 1)
        self.assertEqual(Handler.logins[-1], '/api/v1/auth/direct-login')
        self.assertEqual(Handler.requests[-1][1], 'Bearer automatic-session-token')
        self.assertNotIn(b'automatic-session-token', result.stdout + result.stderr)

    def test_supplied_token_does_not_login_or_elevate_on_error(self):
        before = len(Handler.logins)
        self.assertEqual(self.run_cli('task', '401').returncode, 1)
        self.assertEqual(len(Handler.logins), before)

    def test_login_failure_has_no_query_or_token_output(self):
        Handler.login_failure = True
        before = len(Handler.requests)
        result = self.run_cli('task', '233', token='')
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(Handler.requests), before)
        self.assertEqual(result.stdout, b'')
        self.assertNotIn(b'automatic-session-token', result.stderr)

    def test_continue_after_error(self):
        result = self.run_cli('task', '401', '233')
        self.assertEqual(result.returncode, 1)
        self.assertIn(b'20.663', result.stdout)

    def test_query_encoding_and_endpoint_mapping(self):
        cases = [
            (('audit', '--principal', 'owner a', '--decision', 'DENIED'),
             '/api/v1/security/access/events?principal=owner+a&decision=DENIED&limit=100'),
            (('replicas', '9'), '/api/v1/datasets/9/replicas'),
            (('tc3', '9'), '/api/v1/datasets/9'),
            (('tc4',), '/api/v1/security/dataset-access'),
            (('plan', '65'), '/api/v1/scheduling/plans/65'),
            (('comparison', 'run/a', '--round', '2'), '/api/v1/tasks/runs/run%2Fa/comparison?round=2'),
            (('datasets', '--page', '2'), '/api/v1/datasets?page=2&pageSize=20&query='),
        ]
        for args, path in cases:
            self.assertEqual(self.run_cli(*args).returncode, 0)
            self.assertEqual(Handler.requests[-1][0], path)


if __name__ == '__main__':
    unittest.main()

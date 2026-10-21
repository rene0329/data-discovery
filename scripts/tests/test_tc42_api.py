"""Local HTTP contract checks, no cluster or credentials required."""
import json
import os
from pathlib import Path
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

SCRIPT = Path(__file__).resolve().parents[1] / 'tc42-api.sh'


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

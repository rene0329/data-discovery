"""Local HTTP contract checks, no cluster or credentials required."""
import json
import os
from pathlib import Path
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPT = Path(__file__).resolve().parents[1] / 'tc42-api.sh'


class Handler(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.requests.append((self.path, self.headers.get('Authorization')))
        if self.path.endswith('/401'):
            status, body = 401, b'{"code":401,"msg":"denied"}'
        elif self.path.endswith('/402'):
            status, body = 200, b'{"code":7,"msg":"business error"}'
        elif self.path.endswith('/403'):
            status, body = 200, b'<html>wrong endpoint</html>'
        else:
            status, body = 200, b'{ "code": 0, "data": {"t1": 20.663, "t2": 32.941, "events": []} }'
        self.send_response(status)
        self.end_headers()
        self.wfile.write(body)


class Contract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def run_cli(self, *args, token='test-token'):
        env = dict(os.environ, TOPIC4_TOKEN=token)
        return subprocess.run([str(SCRIPT), '--base-url',
                               'http://127.0.0.1:' + str(self.server.server_port), *args],
                              env=env, capture_output=True)

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

    def test_invalid_id_and_missing_token_never_request(self):
        before = len(Handler.requests)
        for args, token in [(('task', '1;DROP'), 'test'), (('task', '1'), '')]:
            self.assertEqual(self.run_cli(*args, token=token).returncode, 2)
        self.assertEqual(len(Handler.requests), before)

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

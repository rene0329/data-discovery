import importlib.util
import io
import json
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('probe', Path(__file__).parents[1] / 'network-probe-now.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ProbeTest(unittest.TestCase):
    def test_latency_formats_and_failure(self):
        self.assertEqual(probe.parse_latency('rtt min/avg/max/mdev = 1/2.5/4/1 ms'), 2.5)
        self.assertIsNone(probe.parse_latency('100% packet loss'))
        self.assertIsNone(probe.parse_latency('rtt min/avg/max = 1/NaN/4 ms'))

    def test_reverse_bandwidth_and_busy_retry(self):
        responses = [(1, json.dumps({'error': 'server is busy running a test'})),
                     (0, json.dumps({'end': {'sum_received': {'bits_per_second': 1200000}}}))]
        with patch.object(probe, 'execute', side_effect=responses) as execute, patch.object(probe.time, 'sleep'):
            self.assertEqual(probe.bandwidth('pod', '10.0.0.1', 2, True), 1200000)
            self.assertIn('-R', execute.call_args[0])

    def run_fixture(self, permitted, ping_code=0):
        env = {'CENTRAL_AUTH_TOKEN': 'test', 'CENTRAL_PROBE_DECISION_URL': 'decision',
               'CENTRAL_METRICS_URL': 'metrics', 'PROBE_TARGET_EDGES': 'b/a',
               'PROBE_NODE_LAN_IP_OVERRIDES': 'b=172.16.0.2'}
        ds = {'spec': {'template': {'spec': {'containers': [
            {'name': 'network-probe', 'env': [{'name': k, 'value': v} for k, v in env.items()]}]}}}}
        pods = {'items': [{'metadata': {'name': 'pod-a'}, 'spec': {'nodeName': 'a'},
                           'status': {'conditions': [{'type': 'Ready', 'status': 'True'}]}}]}
        nodes = {'items': [{'metadata': {'name': 'b'}, 'status': {'addresses': [
            {'type': 'InternalIP', 'address': '10.0.0.2'}]}}]}
        with patch.object(probe, 'kubectl', side_effect=list(map(json.dumps, [ds, pods, nodes]))) as kubectl, \
                patch.object(probe, 'post', side_effect=[{'data': {'permitted': permitted, 'reason': 'BUSINESS_TRANSFER_ACTIVE'}}, {}]) as post, \
                patch.object(probe, 'execute', return_value=(ping_code, '10 packets transmitted, 8 received, 20% packet loss\nrtt min/avg/max = 1/2/3 ms')) as execute, \
                patch.object(probe, 'bandwidth', side_effect=[4000000, 3000000]), patch('sys.stdout', new_callable=io.StringIO) as stdout:
            if permitted:
                probe.run()
                report = post.call_args[0][2][0]
                self.assertEqual(report['latencyMs'], 2)
                self.assertEqual(report['bandwidthBps'], 3000000)
                self.assertEqual(report['sourceNode'], 'a')
                self.assertIn('172.16.0.2', execute.call_args[0])
                self.assertIn('10', execute.call_args[0])
                self.assertIn('20% packet loss', stdout.getvalue())
                self.assertIn('rtt min/avg/max = 1/2/3 ms', stdout.getvalue())
                self.assertIn('a ↔ b | 2.000 | 20% | 已写回', stdout.getvalue())
                self.assertIn('共 1 条，成功 1，跳过 0，失败 0', stdout.getvalue())
                self.assertNotIn('Mbps', stdout.getvalue())
                self.assertNotIn('带宽', stdout.getvalue())
            else:
                with self.assertRaises(SystemExit):
                    probe.run()
                self.assertIn('a ↔ b | 未测得 | 未知 | 跳过：BUSINESS_TRANSFER_ACTIVE', stdout.getvalue())
                self.assertIn('共 1 条，成功 0，跳过 1，失败 0', stdout.getvalue())
                execute.assert_not_called()
                self.assertEqual(post.call_count, 1)
            self.assertTrue(all(call[0][0] == 'get' for call in kubectl.call_args_list))

    def test_reports_metrics_without_changing_daemonset(self):
        self.run_fixture(True)

    def test_partial_loss_keeps_successful_reply_average(self):
        self.run_fixture(True, ping_code=1)

    def test_busy_link_is_skipped_without_overwriting_metrics(self):
        self.run_fixture(False)


if __name__ == '__main__':
    unittest.main()

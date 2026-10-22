"""Task-only evidence reports and pre-task snapshot provenance; no cluster required."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('snapshot', str(ROOT / 'tc42_snapshot.py'))
snapshot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(snapshot)


class SnapshotTests(unittest.TestCase):
    def test_selects_latest_matching_snapshot_finished_before_creation(self):
        with tempfile.TemporaryDirectory() as root:
            snapshot.save(root, '1,4,6', '90', 'older\n')
            snapshot.save(root, '6,1,4', '99', 'before\n')
            snapshot.save(root, '1,4,6', '101', 'after\n')
            snapshot.save(root, '1,4', '99', 'different\n')
            self.assertEqual(snapshot.select(root, '[1,4,6]', 100)[2], 'before\n')
            self.assertIsNone(snapshot.select(root, '[7]', 100))

    def test_modified_snapshot_is_not_used(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(snapshot.save(root, '1', '99', 'original'))
            payload = json.loads(path.read_text())
            payload['content'] = 'edited'
            path.write_text(json.dumps(payload))
            self.assertIsNone(snapshot.select(root, '[1]', 100))


STUB = r'''
source "$SCRIPT"
backend_init() { :; }
mysql_raw() {
  case "$1" in
    *"SELECT execution_mode, dataset_ids_json"*) printf '%s\t[1,4,6]\t100\n' "$MODE" ;;
    *"SELECT COUNT(*) FROM task_management"*) echo 1 ;;
    *"SELECT execution_mode FROM"*) echo "$MODE" ;;
    *"COUNT(*)"*) echo 0 ;;
    *) echo 'fixture-event' ;;
  esac
}
mysql_table() { echo "TABLE task_id=$EXPECTED_TASK"; }
mysql_vertical() { echo "TASK task_id=$EXPECTED_TASK"; }
main report "$EXPECTED_TASK"
'''


class ReportTests(unittest.TestCase):
    def report(self, mode, task='900', baseline=False):
        with tempfile.TemporaryDirectory() as root:
            if baseline:
                snapshot.save(root, '1,4,6', '99', '真实测试前副本记录\n')
            env = dict(os.environ, SCRIPT=str(ROOT / 'show-tc42-evidence.sh'), MODE=mode,
                       EXPECTED_TASK=task, TC42_SNAPSHOT_DIR=root)
            result = subprocess.run(['bash', '-c', STUB], env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            return result.returncode, result.stdout.decode(), result.stderr.decode()

    def test_centralized_and_distributed_reuse_the_same_report(self):
        for mode, title in [('CENTRALIZED', '测试用例一'), ('IN_PLACE', '测试用例二')]:
            code, output, error = self.report(mode, baseline=True)
            self.assertEqual(code, 0, error)
            for section in [title, '一、测试前数据副本记录', '真实测试前副本记录',
                            '二、任务后端执行结果', '三、任务调度方案与执行证据',
                            '四、数据迁移状态与访问审计']:
                self.assertIn(section, output)
            self.assertNotIn('五、集中式计算', output)

    def test_missing_baseline_is_explicit_and_never_reconstructed(self):
        code, output, error = self.report('CENTRALIZED')
        self.assertEqual(code, 0, error)
        self.assertIn('不能用当前副本状态代替测试前状态', output)
        self.assertIn('TASK task_id=900', output)

    def test_comparison_adds_speedup_only_for_the_requested_task(self):
        code, output, error = self.report('COMPARISON', '901')
        self.assertEqual(code, 0, error)
        self.assertIn('五、集中式计算／原位计算计时与数据移动加速比', output)
        self.assertIn('T1/T2', output)
        self.assertNotIn('task_id=900', output)

    def test_snapshot_query_failure_does_not_save_a_successful_snapshot(self):
        with tempfile.TemporaryDirectory() as root:
            env = dict(os.environ, SCRIPT=str(ROOT / 'show-tc42-evidence.sh'), TC42_SNAPSHOT_DIR=root)
            command = 'source "$SCRIPT"; backend_init() { :; }; print_metadata() { :; }; mysql_table() { return 1; }; main snapshot 1'
            result = subprocess.run(['bash', '-c', command], env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(list(Path(root).glob('baseline-*.json')), [])

    def test_task_id_cannot_inject_shell_or_sql(self):
        code, output, error = self.report('CENTRALIZED', '1; echo injected')
        self.assertNotEqual(code, 0)
        self.assertIn('必须是正整数', error)
        self.assertNotIn('TASK task_id=', output)


if __name__ == '__main__':
    unittest.main()

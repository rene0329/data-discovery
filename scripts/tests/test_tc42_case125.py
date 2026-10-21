import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


class Case125Tests(unittest.TestCase):
    def run_command(self, args):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            shutil.copy2(str(SCRIPTS / 'tc42-case125.sh'), str(root / 'tc42-case125.sh'))
            stub = root / 'show-tc42-evidence.sh'
            stub.write_text('#!/bin/bash\necho "$*" >> "$CALLS"\n[[ "$2" != 999 ]]\n')
            stub.chmod(0o755)
            calls = root / 'calls'
            result = subprocess.run(['bash', str(root / 'tc42-case125.sh')] + args,
                                    env=dict(os.environ, CALLS=str(calls)),
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            return result.returncode, calls.read_text().splitlines() if calls.exists() else []

    def test_one_task(self):
        self.assertEqual(self.run_command(['--task_id', '228']), (0, ['report 228']))

    def test_layout_options_do_not_change_task_selection(self):
        self.assertEqual(self.run_command(['--task_id', '228', '--width', '80']), (0, ['report 228']))
        self.assertEqual(self.run_command(['--raw', '--task_id', '228']), (0, ['report 228']))
        self.assertNotEqual(self.run_command(['--width', '10', '--task_id', '228'])[0], 0)

    def test_multiple_formats_and_repeated_option(self):
        for args in [['--task_id', '228', '231', '274'], ['--task_id', '228,231,274'],
                     ['--task_id', '228，231，274'], ['--task_id', '228', '--task_id', '231', '274']]:
            self.assertEqual(self.run_command(args), (0, ['report 228', 'report 231', 'report 274']))

    def test_invalid_inputs_do_not_query_any_task(self):
        for args in [[], ['--task_id'], ['--task_id', '1; echo bad'], ['--task_id', '228,'],
                     ['--task_id', '228', '0'], ['--unknown', '228']]:
            code, calls = self.run_command(args)
            self.assertNotEqual(code, 0)
            self.assertEqual(calls, [])

    def test_failure_does_not_skip_remaining_tasks(self):
        code, calls = self.run_command(['--task_id', '999', '228'])
        self.assertEqual(code, 1)
        self.assertEqual(calls, ['report 999', 'report 228'])


if __name__ == '__main__':
    unittest.main()

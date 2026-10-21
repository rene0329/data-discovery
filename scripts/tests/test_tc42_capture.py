"""Contract tests for screenshot-command orchestration; no cluster required."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]

API_STUB = '''#!/usr/bin/env python3
import json, os, sys
args=sys.argv[1:]
with open(os.environ['API_CALLS'], 'a') as stream: stream.write(json.dumps(args)+'\\n')
kind, number=args[1], int(args[2])
if kind=='plan':
    data={'plan':{'planId':number,'algorithmName':'热敏存储'},'assignments':[{'datasetId':7}]}
else:
    modes={228:'CENTRALIZED',231:'IN_PLACE',900:'CENTRALIZED'}
    datasets={228:[1,4,6],231:[1,4,6],230:[7],244:[7],232:[3],233:[1,4,6],235:[3],900:[1,4,6]}
    mode=modes.get(number,'COMPARISON')
    if os.environ.get('WRONG_MODE')=='1' and number==228: mode='IN_PLACE'
    data={'task':{'taskId':number,'executionMode':mode,'datasetIdsJson':json.dumps(datasets.get(number,[5])), 'status':'已完成'}}
print(json.dumps({'code':0,'data':data}))
'''
EVIDENCE_STUB = '''#!/usr/bin/env python3
import json, os, sys
args=sys.argv[1:]
with open(os.environ['EVIDENCE_CALLS'], 'a') as stream: stream.write(json.dumps(args)+'\\n')
print('evidence '+json.dumps(args))
if os.environ.get('FAIL_TASK')=='1' and args[:2]==['task','228']: sys.exit(7)
'''


class Capture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for name, source in [('api', API_STUB), ('evidence', EVIDENCE_STUB)]:
            file = self.root / name
            file.write_text(source, encoding='utf-8')
            file.chmod(0o700)
        self.env = dict(os.environ, TC42_API_SCRIPT=str(self.root/'api'),
                        TC42_EVIDENCE_SCRIPT=str(self.root/'evidence'),
                        API_CALLS=str(self.root/'api-calls'), EVIDENCE_CALLS=str(self.root/'calls'),
                        PYTHONIOENCODING='utf-8')
        # Ignore any task overrides or failure switches from the invoking shell.
        for key in ['CENTRAL_TASK_ID','DISTRIBUTED_TASK_ID','HEAT_TASK_ID','BEFORE_TASK_ID','AFTER_TASK_ID',
                    'PLAN_ID','AUTH_TASK_ID','MULTI_TASK_ID','CATDOG_TASK_ID','NLP_TASK_ID',
                    'REPEAT_TASK_IDS_TEXT','WRONG_MODE','FAIL_TASK']:
            self.env.pop(key, None)

    def tearDown(self):
        self.temp.cleanup()

    def run_capture(self, case, *args):
        return subprocess.run([str(SCRIPTS/'tc42-capture.sh'), str(case),
                               '--output-dir', str(self.root/'results'), *args],
                              env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def calls(self):
        file = self.root/'calls'
        return [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []

    def test_all_uses_actual_tasks_and_every_document_command_group(self):
        result = self.run_capture('all')
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        calls = self.calls()
        for task in ['228','231','230','244','232','233','234','235']:
            self.assertTrue(any(c[:2]==['task',task] for c in calls))
        self.assertIn(['access','owner-a','3','232'], calls)
        self.assertIn(['plan','65','--records-only','--log-since','1h'], calls)
        self.assertIn(['speedup','233','234','235','236','237','238','239','240'], calls)
        for task in range(236,241):
            self.assertIn(['decision',str(task),'--selected'], calls)
        self.assertTrue(set(c[0] for c in calls).issubset({'baseline','decision','task','heat','plan','access','speedup'}))
        runs = list((self.root/'results').iterdir())
        self.assertEqual(len(runs), 1)
        self.assertEqual(len((runs[0]/'index.tsv').read_text(encoding='utf-8').splitlines()), 28)
        self.assertFalse(any('heat-before' in f.name for f in runs[0].rglob('*.txt')))

    def test_before_stage_captures_only_actual_current_snapshot_without_task_validation(self):
        self.assertEqual(self.run_capture(3, '--stage','before').returncode, 0)
        self.assertEqual(self.calls(), [['heat','7','9'],['baseline','7','9']])
        self.assertFalse((self.root/'api-calls').exists())

    def test_storage_stage_keeps_original_wait_and_live_check(self):
        self.assertEqual(self.run_capture(3,'--stage','after-storage','--wait-seconds','90').returncode, 0)
        self.assertIn(['plan','65','--wait-seconds','90','--log-since','1h'],self.calls())
        self.assertFalse(any('--records-only' in c for c in self.calls()))

    def test_failure_is_saved_and_does_not_hide_following_evidence(self):
        self.env['FAIL_TASK']='1'
        result=self.run_capture(1)
        self.assertEqual(result.returncode,1)
        self.assertIn(['decision','228','--selected'],self.calls())
        index=next((self.root/'results').rglob('index.tsv')).read_text(encoding='utf-8')
        self.assertIn('\t7\t',index)

    def test_wrong_real_task_is_rejected_before_screenshot_commands(self):
        self.env['WRONG_MODE']='1'
        self.assertEqual(self.run_capture(1).returncode,2)
        self.assertEqual(self.calls(),[])

    def test_actual_task_override_propagates_to_all_queries(self):
        self.assertEqual(self.run_capture(1,'--central-task','900').returncode,0)
        self.assertTrue(any(c[:2]==['task','900'] for c in self.calls()))
        self.assertFalse(any('228' in c for c in self.calls()))

    def test_dry_run_does_not_query_or_create_output(self):
        self.assertEqual(self.run_capture('all','--dry-run').returncode,0)
        self.assertFalse((self.root/'results').exists())
        self.assertFalse((self.root/'api-calls').exists())
        self.assertEqual(self.calls(),[])

    def test_repeats_must_be_distinct_and_have_five_tasks(self):
        for ids in ['236 237','236 237 238 239 239']:
            self.assertEqual(self.run_capture(5,'--stage','repeat-summary','--repeat-tasks',ids).returncode,2)
        self.assertFalse((self.root/'results').exists())

    def test_runs_do_not_overwrite_each_other(self):
        for _ in range(2):
            self.assertEqual(self.run_capture(3,'--stage','decay').returncode,0)
        self.assertEqual(len(list((self.root/'results').iterdir())),2)


    def api_calls(self):
        file=self.root/'api-calls'
        return [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []

    def test_case5_each_type_queries_only_the_supplied_task(self):
        for stage, task in [('multi','233'),('single','234'),('single','235'),('repeat','236')]:
            for name in ['calls','api-calls']:
                file=self.root/name
                if file.exists(): file.unlink()
            result=self.run_capture(5,'--stage',stage,'--task-id',task)
            self.assertEqual(result.returncode,0,result.stderr.decode())
            self.assertEqual(self.api_calls(),[['--raw','task',task]])
            self.assertEqual(self.calls(),[['task',task,'--log-since','1h'],
                                           ['decision',task,'--selected'],['speedup',task]])

    def test_case5_wrong_type_is_rejected_before_evidence(self):
        for stage, task in [('multi','234'),('single','233'),('repeat','235')]:
            self.assertEqual(self.run_capture(5,'--stage',stage,'--task-id',task).returncode,2)
            self.assertEqual(self.calls(),[])

    def test_case5_requires_explicit_actual_ids(self):
        for args in [(),('--stage','multi'),('--stage','single'),('--stage','repeat'),('--stage','repeat-summary')]:
            self.assertEqual(self.run_capture(5,*args).returncode,2)
        self.assertFalse((self.root/'results').exists())
        self.assertEqual(self.api_calls(),[])

    def test_case5_repeat_round_does_not_require_five_completed_tasks(self):
        self.env['REPEAT_TASK_IDS_TEXT']='236'
        result=self.run_capture(5,'--stage','repeat','--task-id','236')
        self.assertEqual(result.returncode,0,result.stderr.decode())
        self.assertEqual(self.api_calls(),[['--raw','task','236']])

    def test_case5_repeat_summary_has_no_multi_or_single_queries(self):
        result=self.run_capture(5,'--stage','repeat-summary','--repeat-tasks','236 237 238 239 240')
        self.assertEqual(result.returncode,0,result.stderr.decode())
        self.assertEqual(len(self.api_calls()),5)
        self.assertEqual(self.calls(),[['decision',str(i),'--selected'] for i in range(236,241)]+
                                     [['speedup','236','237','238','239','240']])

    def test_case5_positional_entry_and_no_argument_guidance(self):
        result=subprocess.run([str(SCRIPTS/'tc42-case5.sh')],env=self.env,
                              stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.assertEqual(result.returncode,0)
        self.assertFalse((self.root/'api-calls').exists())
        result=subprocess.run([str(SCRIPTS/'tc42-case5.sh'),'single','235',
                               '--output-dir',str(self.root/'results')],env=self.env,
                              stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.assertEqual(result.returncode,0,result.stderr.decode())
        self.assertEqual(self.api_calls(),[['--raw','task','235']])

    def test_all_wrappers_target_their_case(self):
        for case in range(1,6):
            args=[str(SCRIPTS/('tc42-case%s.sh'%case))]
            if case==5: args += ['multi','233']
            result=subprocess.run(args+['--dry-run'],env=self.env,
                                  stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            self.assertEqual(result.returncode,0)
            self.assertIn(('TC-4.2.%s'%case).encode(),result.stdout)


if __name__=='__main__': unittest.main()

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
STUB = '''#!/usr/bin/env python3
import json,os,sys
args=sys.argv[1:]
with open(os.environ['CALLS'],'a') as stream: stream.write(json.dumps(args)+'\\n')
if args[:2]==['get','secret']: print('ZmFrZQ==')
elif args[:2]==['config','current-context']: print('fixture-context')
elif args and args[0]=='logs': print('[WARN] request result=FAILED reason=DATASET_ACCESS_DENIED')
elif args and args[0]=='exec':
    if '--xml' in args: print('<resultset><row><field name="event_id">1</field></row></resultset>')
    else: print('1')
'''


class Case4Tests(unittest.TestCase):
    def run_command(self,args):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for file in ('tc42-case4.sh','show-tc42-evidence.sh','tc42_table.py','computation_terms.py'):
                shutil.copy2(str(ROOT/file),str(root/file))
            tools=root/'bin'; tools.mkdir()
            command=tools/'kubectl';command.write_text(STUB);command.chmod(0o755)
            calls=root/'calls'
            env=dict(os.environ,PATH=str(tools)+os.pathsep+os.environ['PATH'],CALLS=str(calls),PYTHONDONTWRITEBYTECODE='1')
            result=subprocess.run(['bash',str(root/'tc42-case4.sh')]+args,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            requests=[json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
            return result,requests

    def test_denial_is_scoped_to_user_dataset_action_and_time(self):
        result,calls=self.run_command(['denied','--user','owner-a','--dataset_id','3','--since','7d'])
        self.assertEqual(result.returncode,0,result.stderr)
        sql=[c[-1] for c in calls if '-e' in c]
        audit=next(s for s in sql if 'FROM dataset_access_audit_event' in s)
        for value in ("principal='owner-a'","dataset_id='3'","decision='DENIED'","action='TASK_CREATE'",'604800 SECOND'):
            self.assertIn(value,audit)
        self.assertTrue(all(s.lstrip().startswith('SELECT') for s in sql))

    def test_grants_show_validity_without_token_contents(self):
        result,calls=self.run_command(['granted','--user','owner-a','--dataset_id','3','--width','100'])
        self.assertEqual(result.returncode,0,result.stderr)
        sql=[c[-1] for c in calls if '-e' in c]
        grants=next(s for s in sql if 'FROM dataset_access_grant g' in s)
        for field in ('created_at','expires_at','ttl_seconds','remaining_seconds','grant_status_now'):
            self.assertIn(field,grants)
        self.assertNotIn('password_hash',' '.join(sql))
        self.assertNotIn('token_version',' '.join(sql))
        self.assertIn('tc42-case125.sh',result.stdout.decode())

    def test_bad_arguments_do_not_query_backend(self):
        for args in [['denied','--user',"x' OR 1=1",'--dataset_id','3'],
                     ['denied','--user','owner-a','--dataset_id','0'],
                     ['granted','--user','owner-a','--dataset_id','3','--since','366d'],
                     ['denied','--user','owner-a','--dataset_id','3','--limit','201'],
                     ['denied']]:
            result,calls=self.run_command(args)
            self.assertNotEqual(result.returncode,0)
            self.assertEqual(calls,[])

    def test_watch_does_not_read_database_credentials(self):
        result,calls=self.run_command(['watch'])
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(len(calls),1)
        self.assertEqual(calls[0][0],'logs')
        self.assertIn('DATASET_ACCESS_DENIED',result.stdout.decode())


if __name__=='__main__': unittest.main()

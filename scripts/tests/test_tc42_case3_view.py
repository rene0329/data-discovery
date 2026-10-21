import importlib.util
from pathlib import Path
import sys
import subprocess
import os
import unittest
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location('case3', str(ROOT / 'tc42_case3_view.py'))
view = importlib.util.module_from_spec(spec)
spec.loader.exec_module(view)


def state(heat):
    return '<resultset><row><field name="dataset_id">7</field><field name="data_heat">{}</field></row></resultset>'.format(heat)


def task(number, start, finish, **changes):
    row = dict(task_id=str(number), dataset_ids_json='[7]',runtime_image_id='4',
               resource_overrides_json=None, execution_mode='COMPARISON',status='已完成',
               execution_evidence_complete='1',started_epoch=str(start),finished_epoch=str(finish))
    row.update(changes)
    return row


class Case3Tests(unittest.TestCase):
    def test_heat_delta_preserves_decline_instead_of_inventing_an_increase(self):
        self.assertEqual(view.heat_delta(state(10),state(19))[0]['heat_delta'],9)
        self.assertEqual(view.heat_delta(state(19),state(10))[0]['heat_delta'],-9)

    def test_control_dataset_must_not_be_in_heating_task(self):
        self.assertTrue(view.task_errors([task(1,1,2,dataset_ids_json='[7,9]')],['1'],'7','9'))
        self.assertFalse(view.task_errors([task(1,1,2)],['1'],'7','9'))

    def test_comparison_requires_matching_resources_and_complete_ordered_tasks(self):
        self.assertFalse(view.task_errors([task(1,1,2),task(2,3,4)],['1','2'],'7','9'))
        for changes in [dict(runtime_image_id='5'),dict(status='执行失败'),
                        dict(execution_evidence_complete='0'),dict(started_epoch='1')]:
            self.assertTrue(view.task_errors([task(1,1,2),task(2,3,4,**changes)],['1','2'],'7','9'))

    def test_latency_pairs_same_mode_and_does_not_treat_null_as_zero(self):
        source = '<resultset>'
        for task_id, mode, value in [('230','IN_PLACE','6.933'),('244','IN_PLACE','5.08'),
                                      ('230','CENTRALIZED','null'),('244','CENTRALIZED','null')]:
            values = dict(task_id=task_id,dataset_id='7',execution_mode=mode,latency_ms=value)
            source += '<row>' + ''.join('<field name="{}">{}</field>'.format(k,escape(v)) for k,v in values.items()) + '</row>'
        source += '</resultset>'
        result = subprocess.run([sys.executable,str(ROOT/'tc42_case3_view.py'),'latency','--ids','230','244','--width','100'],
                                input=source.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                                env=dict(os.environ,PYTHONIOENCODING='utf-8',PYTHONDONTWRITEBYTECODE='1'))
        self.assertEqual(result.returncode,0,result.stderr)
        output=result.stdout.decode()
        self.assertIn('1.853',output)
        self.assertIn('—',output)
        self.assertIn('不按0处理',output)

    def test_missing_task_is_not_an_empty_success(self):
        self.assertTrue(view.task_errors([],['1'],'7','9'))


if __name__ == '__main__':
    unittest.main()

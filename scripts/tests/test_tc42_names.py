import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tc42_names import project, text
from tc42_table import format_data, width


class NameDisplayTest(unittest.TestCase):
    @patch.dict(os.environ, {'TC42_DATASET_NAMES': '{"7":"Tiny ImageNet","9":"CIFAR-10"}'})
    def test_tables_and_lists_use_names_without_changing_internal_rows(self):
        original = {'dataset_id':'7','dataset_name':'Tiny ImageNet','duration_ms':'0'}
        headers, rows = project(list(original), [original])
        self.assertEqual(headers, ['name','duration_ms'])
        self.assertEqual(rows[0]['name'], 'Tiny ImageNet')
        self.assertEqual(original['dataset_id'], '7')
        for size in (60,80,100):
            output = format_data(list(original), [original], size)
            self.assertNotIn('dataset_id', '\n'.join(output))
            self.assertTrue(all(width(line) <= size for line in output))
        self.assertEqual(project(['dataset_ids_json'],[{'dataset_ids_json':'[7,9]'}])[1][0]['names'],
                         'Tiny ImageNet, CIFAR-10')

    @patch.dict(os.environ, {'TC42_DATASET_NAMES': '{"7":"Tiny ImageNet"}'})
    def test_logs_do_not_replace_task_or_replica_ids_or_expose_unknown_dataset_ids(self):
        output = text('taskId=7 datasetId=7 replica_id=7 dataset_id=99 dataset_ids=[7]')
        self.assertIn('taskId=7', output)
        self.assertIn('replica_id=7', output)
        self.assertIn('Tiny ImageNet', output)
        self.assertNotIn('dataset_id', output)
        self.assertNotIn('99', output)

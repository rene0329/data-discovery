import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tc42_dataset_overview import number, size, overview
from tc42_table import render_table, width


class DatasetOverviewTest(unittest.TestCase):
    def test_units_zero_and_unknown(self):
        self.assertEqual('0', number('0'))
        self.assertEqual('—', number(None))
        self.assertEqual('1 GiB', size(str(1024 ** 3)))
        self.assertEqual('1.5 MiB', size(str(1536 * 1024)))
        self.assertEqual('—', size(None))

    def test_resource_availability_and_width(self):
        headers, rows = overview('nodes', [dict(node_name='master-40', site_code='core',
            type='compute-storage', max_cpu='128', current_cpu='1.535',
            max_memory='503.54', current_memory=None, replicas='0', status='ONLINE')])
        self.assertEqual('126.47/128', rows[0]['cpu'])
        self.assertEqual('—/503.54', rows[0]['mem_GiB'])
        for columns in (80, 100, 110):
            self.assertTrue(all(width(line) <= columns for line in render_table(headers, rows, columns)))

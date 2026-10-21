import importlib.util
from pathlib import Path
import unittest
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('tc42_table', str(ROOT / 'tc42_table.py'))
tables = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tables)


def xml(rows):
    return '<resultset xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">' + ''.join(
        '<row>' + ''.join('<field name="{}"{}>{}</field>'.format(key,
            ' xsi:nil="true"' if value is None else '', '' if value is None else escape(str(value)))
            for key, value in row.items()) + '</row>' for row in rows) + '</resultset>'


class TableTests(unittest.TestCase):
    def test_long_event_names_and_hashes_are_wrapped_and_tables_split(self):
        source = xml([dict(event_id=1862, dataset_id=6, execution_mode='CENTRALIZED',
                           event_type='DATA_PREPARATION_READY', node_name='master-40',
                           bytes_processed=4853577, duration_ms=5895, attempt=1,
                           occurred_at='2026-10-18 10:11:56.000',
                           job_name='centralized-yelp.npz-b4cfe871',
                           pod_name='centralized-yelp.npz-b4cfe871-9m5jv',
                           checksum_sha256='3c19b5ac' * 8, output_checksum_sha256=None)])
        for width in (60, 80, 100, 120):
            lines = tables.format_xml(source, width)
            self.assertTrue(all(tables.width(line) <= width for line in lines), '\n'.join(lines))
            output = '\n'.join(lines)
            for title in ('执行状态', '执行计时', 'Job / Pod 关联', '文件校验'):
                self.assertIn(title, output)
            self.assertNotIn('output_checksum_sha256', output)
            self.assertIn('5895', output)
            self.assertIn('1862', output)

    def test_null_columns_are_hidden_but_zero_and_missing_timing_are_preserved(self):
        source = xml([dict(event_id=1, duration_ms=None, bytes_processed=0, checksum_sha256=None)])
        headers, rows = tables.parse(source)
        self.assertEqual(rows[0]['bytes_processed'], '0')
        self.assertIsNone(rows[0]['duration_ms'])
        output = '\n'.join(tables.format_xml(source, 80))
        self.assertIn('duration_ms', output)
        self.assertNotIn('checksum_sha256', output)
        self.assertIn('—', output)
        self.assertIn('0', output)

    def test_xml_field_separators_and_multiline_values_are_preserved(self):
        _, rows = tables.parse(xml([{'task_id': 1, 'schedule': 'a|b -> c\n中文 & <value>'}]))
        self.assertEqual(rows[0]['schedule'], 'a|b -> c\n中文 & <value>')
        output = tables.format_xml(xml(rows), 60, vertical=True)
        self.assertTrue(all(tables.width(line) <= 60 for line in output))
        self.assertIn('中文 & <value>', '\n'.join(output))

    def test_empty_results_are_explicit(self):
        self.assertEqual(tables.format_xml('<resultset/>'), ['  (no records)'])


if __name__ == '__main__':
    unittest.main()

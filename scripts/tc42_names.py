"""Resolve dataset identifiers only at the presentation boundary."""
import json
import os
import re
import sys


def catalog():
    try:
        value = json.loads(os.environ.get('TC42_DATASET_NAMES', '{}'))
        return value if isinstance(value, dict) else {}
    except ValueError:
        return {}


def name(value):
    if value is None:
        return None
    return catalog().get(str(value), '未登记数据集')


def text(value):
    if 'TC42_DATASET_NAMES' not in os.environ:
        return value
    def many(match):
        try:
            values = json.loads(match.group(2)) if match.group(2).startswith('[') else match.group(2).split(',')
            return 'names=' + json.dumps([name(item) for item in values], ensure_ascii=False)
        except ValueError:
            return 'names=未记录'
    value = re.sub(r'\b(dataset_ids_json|dataset_ids|datasetIds)\s*[:=]\s*(\[[^\]]*\]|\d+(?:,\d+)*)', many, value)
    return re.sub(r'\b(?:registered_dataset_id|dataset_id|datasetId)\s*[:=]\s*(\d+)\b',
                  lambda m: 'name=' + json.dumps(name(m.group(1)), ensure_ascii=False), value)


def project(headers, rows):
    if 'TC42_DATASET_NAMES' not in os.environ:
        return headers, rows
    single = {'dataset_id', 'datasetId', 'registered_dataset_id'}
    multiple = {'dataset_ids_json', 'dataset_ids', 'datasetIds'}
    converted = []
    for row in rows:
        record = {}
        for key in headers:
            value = row.get(key)
            if key in single:
                record['name'] = row.get('name') or row.get('dataset_name') or row.get('datasetName') or name(value)
            elif key in multiple:
                try:
                    values = json.loads(value or '[]')
                    record['names'] = ', '.join(name(item) for item in values)
                except (ValueError, TypeError):
                    record['names'] = None
            elif key == 'name' and record.get('name') and not value:
                continue
            else:
                record[key] = text(value) if isinstance(value, str) else value
        if 'name' in record:
            for alias in ('dataset_name', 'datasetName'):
                if record.get(alias) == record['name']:
                    del record[alias]
        converted.append(record)
    columns = []
    for key in headers:
        key = 'name' if key in single else 'names' if key in multiple else key
        if key not in columns and any(key in row for row in converted):
            columns.append(key)
    return columns, converted


if __name__ == '__main__':
    if len(sys.argv) > 1:
        print(name(sys.argv[1]) or '未记录')
    else:
        for line in sys.stdin:
            print(text(line), end='', flush=True)

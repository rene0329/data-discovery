#!/usr/bin/env python3
"""Update registered dataset introductions through the versioned metadata API."""
import argparse
import json
import os
from pathlib import Path
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', required=True)
parser.add_argument('--direct-login', action='store_true', help='Use the configured direct-login identity')
parser.add_argument('--apply', action='store_true')
parser.add_argument('--backup', type=Path, help='Required for --apply; created exclusively before changes')
args = parser.parse_args()
if args.apply and not args.backup:
    parser.error('--apply requires --backup')
token = os.environ.get('TOPIC4_TOKEN', '')


def request(path, data=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if data is not None:
        headers['Idempotency-Key'] = str(uuid.uuid4())
    req = urllib.request.Request(args.base_url.rstrip('/') + path,
                                 data=json.dumps(data).encode() if data is not None else None,
                                 headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as response:
        result = json.load(response)
    if result.get('code') not in (0, 200):
        raise RuntimeError(result.get('msg', 'API request failed'))
    return result['data']


if args.direct_login:
    token = request('/api/v1/auth/direct-login', {}, 'POST')['token']
if not token:
    parser.error('Set TOPIC4_TOKEN or use --direct-login')
catalog = json.loads((Path(__file__).parent / 'catalog/dataset-descriptions.json').read_text())
descriptions = {item['datasetCode']: item['description'] for item in catalog}
datasets = []
page = 1
while True:
    result = request('/api/v1/datasets?pageSize=100&page=' + str(page))
    datasets.extend(result['list'])
    if len(datasets) >= result['total']:
        break
    if not result['list']:
        raise RuntimeError('Dataset list changed during pagination')
    page += 1
changes = [item for item in datasets if item['datasetCode'] in descriptions
           and item.get('description') != descriptions[item['datasetCode']]]
if args.apply:
    with args.backup.open('x', encoding='utf-8') as output:
        json.dump(changes, output, ensure_ascii=False, indent=2)
for item in changes:
    if args.apply:
        description = descriptions[item['datasetCode']]
        updated = request('/api/v1/datasets/' + str(item['datasetId']),
                          {'description': description, 'rowVersion': item['rowVersion']}, 'PATCH')
        if updated['description'] != description:
            raise RuntimeError('Description verification failed')
    print(('Updated' if args.apply else 'Would update') + ': ' + item['datasetCode'])
print('Changed datasets: ' + str(len(changes)))

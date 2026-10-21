#!/usr/bin/env python3
"""Read-only formatting and provenance checks for staged heat-storage evidence."""
import argparse
import json
import os
import sys
from pathlib import Path
from urllib.request import Request, build_opener
from urllib.parse import urlsplit
from tc42_api import login, NoRedirect
from tc42_table import parse, format_xml, format_data, wrap


def heat_delta(before, after):
    def unique(source):
        _, rows = parse(source)
        return {r['dataset_id']: r for r in rows}
    old, new = unique(before), unique(after)
    result = []
    for key in sorted(new, key=int):
        previous = old.get(key, {}).get('data_heat')
        current = new[key].get('data_heat')
        change = None if previous is None or current is None else round(float(current) - float(previous), 2)
        result.append({'dataset_id': key, 'name': new[key].get('name'),
                       'data_heat_before': previous, 'data_heat_after': current, 'heat_delta': change})
    return result


def task_errors(rows, ids, hot, control):
    by_id = {r['task_id']: r for r in rows}
    errors = []
    for task_id in ids:
        row = by_id.get(task_id)
        if row is None:
            errors.append('任务 {} 不存在'.format(task_id))
            continue
        datasets = json.loads(row.get('dataset_ids_json') or '[]')
        if int(hot) not in datasets or int(control) in datasets:
            errors.append('任务 {} 必须包含热数据集且不能包含对照数据集'.format(task_id))
    if len(ids) == 2 and all(i in by_id for i in ids):
        a, b = (by_id[i] for i in ids)
        for field in ('dataset_ids_json', 'runtime_image_id', 'resource_overrides_json', 'execution_mode'):
            left, right = a.get(field), b.get(field)
            if field.endswith('_json'):
                left = json.loads(left) if left else None
                right = json.loads(right) if right else None
                if field == 'dataset_ids_json':
                    left, right = sorted(left or []), sorted(right or [])
            if left != right:
                errors.append('两次任务 {} 不一致'.format(field))
        if not a.get('finished_epoch') or not b.get('started_epoch') or float(a['finished_epoch']) > float(b['started_epoch']):
            errors.append('两次任务时间顺序不明确或发生重叠')
        for row in (a, b):
            if row.get('status') not in ('COMPLETED', '已完成') or row.get('execution_evidence_complete') != '1':
                errors.append('任务 {} 未完成或执行证据不完整'.format(row['task_id']))
    return errors


def preview(width):
    base = os.environ.get('TOPIC4_API_URL', 'http://10.15.16.40:30482').rstrip('/')
    url = urlsplit(base)
    if url.scheme not in ('http', 'https') or not url.netloc or url.username or url.password or url.query or url.fragment:
        raise ValueError('TOPIC4_API_URL 必须是无凭据的 HTTP(S) 地址')
    opener = build_opener(NoRedirect())
    token = os.environ.get('TOPIC4_TOKEN') or login(opener, base, 60)
    if '\n' in token or '\r' in token:
        raise ValueError('令牌格式错误')
    request = Request(base + '/api/v1/scheduling/storage-plans/preview?mode=heat', data=b'',
                      headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/json'}, method='POST')
    with opener.open(request, timeout=60) as response:
        raw = response.read().decode('utf-8')
    result = json.loads(raw)
    if result.get('code') != 0:
        raise ValueError('热敏存储预览失败；未提交或执行方案')
    data = result.get('data') or {}
    Path(os.environ['PREVIEW_OUTPUT']).write_text(raw, encoding='utf-8')
    rows = data.get('placements') or []
    columns = ['datasetId', 'datasetName', 'dataHeat', 'sourceNode', 'targetNode', 'action', 'consumerNodeId', 'reason']
    print('\n'.join(format_data(columns[:-1], rows, width)))
    print('【各数据集的节点选择理由】')
    print('\n'.join(format_data(['datasetId','reason'], rows, width)))
    for notice in data.get('notices') or []:
        print('\n'.join(wrap('说明：' + str(notice), width)))
    if not rows:
        print('当前预览没有需要移动的数据；不能据此重建历史方案的选择原因。')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('action', choices=('delta', 'validate-task', 'compare-check', 'preview', 'logs', 'latency', 'state'))
    p.add_argument('--width', type=int, default=120)
    p.add_argument('--before')
    p.add_argument('--ids', nargs='+', default=[])
    p.add_argument('--hot', default='7')
    p.add_argument('--control', default='9')
    args = p.parse_args()
    if args.action == 'preview':
        preview(args.width)
    elif args.action == 'state':
        _, records = parse(sys.stdin.read())
        unique = {}
        for row in records:
            unique[row['dataset_id']] = row
        print('热度状态：')
        print('\n'.join(format_data(['dataset_id','name','data_heat','heat_updated_at'],list(unique.values()),args.width)))
        print('当前副本位置（以 dataset_id 关联上表）：')
        print('\n'.join(format_data(['dataset_id','replica_id','node_name','node_type','availability','size_bytes'],records,args.width)))
    elif args.action == 'latency':
        _, records = parse(sys.stdin.read())
        pairs = {}
        for row in records:
            pairs.setdefault((row.get('dataset_id'), row.get('execution_mode')), {})[row['task_id']] = row
        output = []
        for key, pair in sorted(pairs.items()):
            a, b = (pair.get(task_id, {}) for task_id in args.ids)
            def number(value):
                try:
                    value = float(value)
                    import math
                    return value if math.isfinite(value) and value >= 0 else None
                except (TypeError, ValueError):
                    return None
            before, after = number(a.get('latency_ms')), number(b.get('latency_ms'))
            output.append({'dataset_id': key[0], 'execution_mode': key[1],
                           'before_task_id': args.ids[0], 'after_task_id': args.ids[1],
                           'latency_ms_before': before, 'latency_ms_after': after,
                           'latency_reduction_ms': None if before is None or after is None else round(before - after, 6)})
        print('\n'.join(format_data(['dataset_id','execution_mode','before_task_id','after_task_id',
                                    'latency_ms_before','latency_ms_after','latency_reduction_ms'], output, args.width)))
        print('latency_reduction_ms=前值−后值；缺失或非数值不按0处理。同一任务同一模式取最后一条已保存的节点选择事件。')
    elif args.action == 'delta':
        rows = heat_delta(Path(args.before).read_text(encoding='utf-8'), sys.stdin.read())
        print('\n'.join(format_data(['dataset_id','name','data_heat_before','data_heat_after','heat_delta'], rows, args.width)))
        print('heat_delta 为两次实际观测差值，包含期间的热度衰减及其他访问，不能全部归因于指定任务。')
    elif args.action in ('validate-task', 'compare-check'):
        _, rows = parse(sys.stdin.read())
        errors = task_errors(rows, args.ids, args.hot, args.control)
        if errors:
            for error in errors:
                print('对照检查：' + error)
            if args.action == 'validate-task':
                return 2
            print('对照条件不足；仅展示原始记录，不据此认定热敏存储带来改善。')
        else:
            print('任务数据集及对照条件核对完成。')
    else:
        content = sys.stdin.read()
        if not content.strip():
            print('当前容器日志中没有匹配记录，可能已因重启或轮转丢失；数据库记录仍可核对。')
        for line in content.splitlines():
            print('\n'.join(wrap(line, args.width)))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print('查询失败：{}；未执行数据迁移。'.format(error), file=sys.stderr)
        sys.exit(1)

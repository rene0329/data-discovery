#!/usr/bin/env python3
"""Width-bounded MySQL XML tables without renaming fields or truncating evidence."""
import argparse
import sys
import unicodedata
import xml.etree.ElementTree as ET

KEYS = ('event_id', 'migration_id', 'replica_id', 'grant_id', 'task_id', 'dataset_id')
IMPORTANT = set(KEYS + ('status', 'event_type', 'execution_mode', 'duration_ms',
                        'data_preparation_ms', 'compute_duration_ms', 'execution_evidence_complete',
                        'decision', 'error_message'))


def width(value):
    return sum(0 if unicodedata.combining(c) else 2 if unicodedata.east_asian_width(c) in 'WF' else 1 for c in value)


def wrap(value, limit):
    out = []
    for paragraph in str(value).split('\n'):
        line = ''
        for char in paragraph:
            if width(line + char) > limit:
                boundary = max(line.rfind(separator) for separator in (' ', '_', '-', '/', '.')) + 1
                if boundary >= len(line) // 3 and boundary > 0:
                    out.append(line[:boundary])
                    line = line[boundary:]
                else:
                    out.append(line)
                    line = ''
            line += char
        out.append(line)
    return out


def parse(source):
    root = ET.fromstring(source)
    headers, rows = [], []
    for row in root.findall('row'):
        values = {}
        for field in row.findall('field'):
            name = field.attrib['name']
            if name not in headers:
                headers.append(name)
            nil = field.attrib.get('{http://www.w3.org/2001/XMLSchema-instance}nil') == 'true'
            values[name] = None if nil else (field.text or '')
        rows.append(values)
    return headers, rows


def display(value):
    return '—' if value is None else str(value)


def render_table(headers, rows, total):
    sizes = [max(4, min(max(30, total - 24) if (h in ('reason', 'schedule', 'message') and len(headers) <= 3) or (h == 'description' and len(headers) <= 4) else 30,
                         max([width(h)] + [width(display(r.get(h))) for r in rows]))) for h in headers]
    while sum(sizes) + 3 * len(sizes) + 1 > total:
        index = max(range(len(sizes)), key=lambda i: sizes[i])
        if sizes[index] <= 4:
            raise ValueError('表格列数超过指定宽度')
        sizes[index] -= 1
    border = '+' + '+'.join('-' * (size + 2) for size in sizes) + '+'
    result = [border]
    for record in [dict(zip(headers, headers))] + rows:
        cells = [wrap(display(record.get(name)), sizes[i]) for i, name in enumerate(headers)]
        for index in range(max(len(cell) for cell in cells)):
            parts = [cell[index] if index < len(cell) else '' for cell in cells]
            result.append('| ' + ' | '.join(part + ' ' * (sizes[i] - width(part)) for i, part in enumerate(parts)) + ' |')
        # Row boundaries make wrapped Job names and hashes unambiguous.
        result.append(border)
    return result


def groups(headers, total):
    key = next((key for key in KEYS if key in headers), headers[0])
    if 'grant_id' in headers:
        requests = [('申请记录', ['grant_id','username','dataset_id','dataset_code','name','reason']),
                    ('有效时间', ['grant_id','created_at','expires_at','ttl_seconds']),
                    ('当前有效性', ['grant_id','grant_status_now','remaining_seconds'])]
        used = set()
        for title, candidates in requests:
            selected = [name for name in candidates if name in headers]
            used.update(selected)
            if len(selected) > 1:
                yield title, selected
        rest = [name for name in headers if name not in used]
        if rest:
            yield '其他字段', ['grant_id'] + rest
    elif 'event_id' in headers and 'decision' in headers:
        requests = [('访问结果', ['event_id','principal','dataset_id','action','decision','reason','created_at']),
                    ('请求关联', ['event_id','request_id','run_id','target_node','policy_rule',
                                  'token_expires_at','client_ip','evidence_scope','file_path'])]
        used = set()
        for title, candidates in requests:
            selected = [name for name in candidates if name in headers]
            used.update(selected)
            if len(selected) > 1:
                yield title, selected
        rest = [name for name in headers if name not in used]
        if rest:
            yield '其他字段', ['event_id'] + rest
    elif 'event_type' in headers and 'event_id' in headers:
        requested = [
            ('执行状态', ['event_id', 'dataset_id', 'execution_mode', 'event_type', 'node_name', 'attempt', 'occurred_at']),
            ('执行计时', ['event_id', 'bytes_processed', 'duration_ms']),
            ('Job / Pod 关联', ['event_id', 'job_name', 'pod_name']),
            ('文件校验', ['event_id', 'checksum_sha256', 'output_checksum_sha256'])]
        used = set()
        for title, candidates in requested:
            chosen = [h for h in candidates if h in headers]
            used.update(chosen)
            if len(chosen) > 1:
                yield title, chosen
        remaining = [h for h in headers if h not in used]
        if remaining:
            yield '其他字段', [key] + remaining
    else:
        # Bound column count even for very narrow terminals; repeat the join key.
        capacity = max(2, min(8, (total - 1) // 7))
        rest = [h for h in headers if h != key]
        for start in range(0, max(1, len(rest)), capacity - 1):
            yield '', [key] + rest[start:start + capacity - 1]


def format_xml(source, total=120, vertical=False):
    headers, rows = parse(source)
    return format_data(headers, rows, total, vertical)


def format_data(headers, rows, total=120, vertical=False):
    if not rows:
        return ['  (no records)']
    hidden = [h for h in headers if h not in IMPORTANT and all(r.get(h) is None for r in rows)]
    headers = [h for h in headers if h not in hidden]
    result = []
    if not vertical and 'event_type' in headers and 'execution_mode' in headers:
        modes = set(row.get('execution_mode') for row in rows)
        if len(modes) == 1 and None not in modes:
            result.append('execution_mode = {} (all rows)'.format(display(next(iter(modes)))))
            headers.remove('execution_mode')
    if vertical:
        key_width = min(36, max(width(h) for h in headers), total // 2)
        for row in rows:
            for name in headers:
                name_lines = wrap(name, key_width)
                value_lines = wrap(display(row.get(name)), total - key_width - 3)
                for index in range(max(len(name_lines), len(value_lines))):
                    label = name_lines[index] if index < len(name_lines) else ''
                    value = value_lines[index] if index < len(value_lines) else ''
                    result.append(label + ' ' * (key_width - width(label)) + ' : ' + value)
    else:
        for title, selected in groups(headers, total):
            if title:
                result.append(title + '  ({} 对应同一条原始记录)'.format('grant_id' if 'grant_id' in selected else 'event_id'))
            shown = rows
            if title in ('执行计时', '文件校验'):
                shown = [row for row in rows if any(row.get(name) is not None for name in selected if name != 'event_id')]
            if not shown:
                result.append('  (no recorded values)')
                continue
            # Narrow terminals may need one more split of a predefined group.
            if 7 * len(selected) + 1 > total:
                join_key = selected[0]
                capacity = max(2, (total - 1) // 7)
                rest = selected[1:]
                for start in range(0, len(rest), capacity - 1):
                    result.extend(render_table([join_key] + rest[start:start + capacity - 1], shown, total))
            else:
                result.extend(render_table(selected, shown, total))
    if hidden:
        result.append('已隐藏全为 NULL 的非关键列；--raw 可查看全部字段。')
    return result


def format_saved_snapshot(content, total):
    """Reflow old saved ASCII tables only when every field boundary is unambiguous."""
    result, block = [], []
    def flush():
        if not block:
            return
        cells = [[part.strip() for part in line.split('|')[1:-1]]
                 for line in block if line.startswith('|') and line.endswith('|')]
        if cells and all(len(row) == len(cells[0]) for row in cells):
            headers = cells[0]
            rows = [dict(zip(headers, [None if value == 'NULL' else value for value in row])) for row in cells[1:]]
            result.extend(format_data(headers, rows, total))
        else:
            result.append('旧快照字段边界不明确；请用 --raw 查看该快照，未重新解释数据。')
        block.clear()
    for line in content.splitlines():
        if line.startswith('+---') or line.startswith('|'):
            block.append(line)
        else:
            flush()
            result.extend(wrap(line, total))
    flush()
    return '\n'.join(result) + '\n'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--width', type=int, default=120)
    parser.add_argument('--vertical', action='store_true')
    args = parser.parse_args()
    if args.width < 60 or args.width > 300:
        parser.error('width must be between 60 and 300')
    try:
        for line in format_xml(sys.stdin.read(), args.width, args.vertical):
            print(line)
    except (ET.ParseError, ValueError) as error:
        print('表格排版失败：{}；可使用 --raw 查询原始输出。'.format(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""Compact current dataset and node overview; no mutations."""
import argparse
from decimal import Decimal, ROUND_HALF_UP
import sys
from tc42_table import parse, render_table


def number(value):
    if value is None:
        return '—'
    return format(Decimal(str(value)).quantize(Decimal('.01'), rounding=ROUND_HALF_UP), 'f').rstrip('0').rstrip('.')


def size(value):
    if value is None:
        return '—'
    amount = Decimal(value)
    for unit in ('B', 'KiB', 'MiB', 'GiB', 'TiB'):
        if amount < 1024 or unit == 'TiB':
            return '{} {}'.format(number(amount), unit)
        amount /= 1024


def overview(kind, rows):
    if kind == 'datasets':
        headers = ['name', 'data_heat', 'node_name', 'site_code', 'availability', 'size']
        return headers, [dict(row, data_heat=number(row.get('data_heat')), size=size(row.get('size_bytes')))
                         for row in rows]
    headers = ['node_name', 'site_code', 'type', 'cpu', 'mem_GiB', 'replicas', 'status']
    result = []
    for row in rows:
        record = dict(row)
        for field, total, used in [('cpu', 'max_cpu', 'current_cpu'),
                                   ('mem_GiB', 'max_memory', 'current_memory')]:
            maximum, current = row.get(total), row.get(used)
            free = None if maximum is None or current is None else max(Decimal(0), Decimal(maximum) - Decimal(current))
            record[field] = '{}/{}'.format(number(free), number(maximum))
        result.append(record)
    return headers, result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('kind', choices=['datasets', 'nodes'])
    parser.add_argument('--width', type=int, default=110)
    args = parser.parse_args()
    _, rows = parse(sys.stdin.read())
    if not rows:
        print('（无记录）')
        return
    headers, rows = overview(args.kind, rows)
    lines = render_table(headers, rows, max(60, min(300, args.width)))
    # Keep header/footer boundaries, avoiding a separator for every single record.
    borders = [i for i, line in enumerate(lines) if line.startswith('+')]
    keep = {borders[0], borders[1], borders[-1]}
    print('\n'.join(line for i, line in enumerate(lines)
                    if not line.startswith('+') or i in keep))


if __name__ == '__main__':
    main()

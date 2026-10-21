#!/usr/bin/env python3
"""Persist and select genuine pre-task snapshots; Python 3.6 standard library."""
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path


def ids(value):
    values = json.loads(value) if value.startswith('[') else value.split(',')
    result = sorted(set(int(item) for item in values))
    if not result or any(item <= 0 for item in result):
        raise ValueError('数据集编号必须为正整数且不能为空')
    return result


def save(directory, dataset_ids, finished, content):
    root = Path(directory)
    root.mkdir(parents=True, exist_ok=True)
    payload = {'version': 1, 'datasetIds': ids(dataset_ids),
               'captureFinishedEpoch': float(finished), 'content': content,
               'sha256': hashlib.sha256(content.encode('utf-8')).hexdigest()}
    handle, name = tempfile.mkstemp(prefix='baseline-', suffix='.json', dir=str(root))
    with os.fdopen(handle, 'w', encoding='utf-8') as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2)
    return name


def select(directory, dataset_ids, task_created):
    expected = ids(dataset_ids)
    candidates = []
    for path in Path(directory).glob('baseline-*.json'):
        try:
            with path.open(encoding='utf-8') as stream:
                payload = json.load(stream)
            digest = hashlib.sha256(payload['content'].encode('utf-8')).hexdigest()
            if payload['version'] != 1 or digest != payload['sha256']:
                raise ValueError('快照内容校验失败')
            if payload['datasetIds'] == expected and float(payload['captureFinishedEpoch']) <= float(task_created):
                candidates.append((float(payload['captureFinishedEpoch']), str(path), payload['content']))
        except (ValueError, KeyError, TypeError, OSError) as error:
            print('警告：跳过不可用快照 {}：{}'.format(path, error), file=sys.stderr)
    return max(candidates) if candidates else None


def main(argv):
    if len(argv) != 5 or argv[1] not in ('save', 'select'):
        raise ValueError('用法：tc42_snapshot.py save|select 目录 数据集编号 采集结束时间戳|任务创建时间戳')
    if argv[1] == 'save':
        print(save(argv[2], argv[3], argv[4], sys.stdin.read()))
    else:
        result = select(argv[2], argv[3], argv[4])
        if result:
            print('快照文件：{}'.format(result[1]))
            print('说明：该快照在任务创建之前采集完成，数据集编号与任务一致。')
            print(result[2], end='' if result[2].endswith('\n') else '\n')
        else:
            print('未找到匹配的测试前快照；不能用当前副本状态代替测试前状态。')
            print('下次请在前端创建任务前执行 snapshot 命令。其余已保存的任务证据仍会继续展示。')


if __name__ == '__main__':
    try:
        main(sys.argv)
    except (ValueError, OSError) as error:
        print('错误：{}'.format(error), file=sys.stderr)
        sys.exit(1)

"""Curated evidence views for TC-4.2; Python 3.6, standard library only."""
import json
import math
import re
import unicodedata
from computation_terms import computation_text

MODES = {'CENTRALIZED': '集中式计算', 'IN_PLACE': '原位计算', 'COMPARISON': '两种模式对比'}
STATES = {'COMPLETED': '已完成', 'RUNNING': '运行中', 'ACCEPTED': '已受理',
          'FAILED': '失败', 'AVAILABLE': '可用', 'USABLE': '可用', 'ACTIVE': '已启用',
          'ONLINE': '在线', 'OFFLINE': '离线', 'HEALTHY': '正常',
          'ALLOWED': '允许', 'DENIED': '拒绝', 'MOVE': '迁移', 'COPY': '复制',
          'COMPUTE': '计算', 'STORAGE': '存储', 'COMPUTE_STORAGE': '计算/存储',
          'CROSS_DOMAIN_ACCESS_DENIED': '跨域访问未获授权'}


def numeric(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def text(value):
    if value is None or value == '':
        return '—'
    if isinstance(value, bool):
        return '是' if value else '否'
    return computation_text(STATES.get(str(value), str(value)))


def size_text(value):
    if not numeric(value) or value < 0:
        return '—'
    units = ('B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB')
    index = 0
    while value >= 1024 and index < len(units) - 1:
        value /= 1024.0
        index += 1
    return '{:g} B'.format(value) if index == 0 else '{:.2f} {}'.format(value, units[index])


def seconds(value):
    return '{:.3f}'.format(value) if numeric(value) and value >= 0 else '—'


def width(value):
    return sum(0 if unicodedata.combining(c) else 2 if unicodedata.east_asian_width(c) in 'WF' else 1 for c in value)


def wrap(value, limit):
    result, line = [], ''
    for char in str(value).replace('\n', ' / '):
        if width(line + char) > limit:
            result.append(line)
            line = ''
        line += char
    result.append(line)
    return result


def table(headers, rows, limits=None):
    if not rows:
        return ['  无记录']
    strings = [[text(cell) for cell in row] for row in rows]
    limits = limits or [24] * len(headers)
    widths = [max(width(headers[i]), min(limits[i], max(width(row[i]) for row in strings))) for i in range(len(headers))]
    def row_lines(row):
        cells = [wrap(cell, widths[i]) for i, cell in enumerate(row)]
        lines = []
        for n in range(max(len(cell) for cell in cells)):
            parts = [(cell[n] if n < len(cell) else '') for cell in cells]
            lines.append('  ' + '  '.join(part + ' ' * (widths[i] - width(part)) for i, part in enumerate(parts)).rstrip())
        return lines
    output = row_lines(headers)
    output.append('  ' + '  '.join('─' * w for w in widths))
    for row in strings:
        output.extend(row_lines(row))
    return output


def decoded(value, fallback):
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, type(fallback)) else fallback
        except ValueError:
            return fallback
    return value if isinstance(value, type(fallback)) else fallback


def items(data):
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return data.get('list', data.get('items', [])) or []
    return []


def context_data(payload):
    return payload.get('data') if isinstance(payload, dict) and payload.get('code') == 0 else None


def dataset_label(dataset_id, context):
    row = context.get('datasets', {}).get(str(dataset_id), {})
    return row.get('name') or '#{}'.format(dataset_id)


def node_label(node_id, context):
    row = context.get('nodes', {}).get(str(node_id), {})
    return row.get('k8sNodeName') or row.get('nodeName') or row.get('displayName') or '#{}'.format(node_id)


def details(event):
    return decoded(event.get('detailsJson'), {})


def task_groups(data):
    task = data.get('task') or {}
    ids = decoded(task.get('datasetIdsJson'), [])
    events = sorted(data.get('events') or [], key=lambda e: e.get('eventId') or 0)
    modes = ['CENTRALIZED', 'IN_PLACE'] if task.get('executionMode') == 'COMPARISON' else [task.get('executionMode')]
    groups = {(m, str(i)): [] for m in modes for i in ids}
    for event in events:
        if event.get('datasetId') is not None:
            groups.setdefault((event.get('executionMode'), str(event['datasetId'])), []).append(event)
    result = []
    for (mode, dataset_id), history in groups.items():
        placements = [e for e in history if e.get('eventType') == 'PLACEMENT_SELECTED']
        placement = details(placements[-1]) if placements else {}
        executions = [e for e in history if not str(e.get('eventType', '')).startswith('PLACEMENT_')]
        # Select the most recent execution attempt, never borrow a prior attempt's success.
        if executions:
            last = executions[-1]
            executions = [e for e in executions if all(last.get(k) is None or e.get(k) == last[k]
                          for k in ('jobName', 'podName', 'attempt'))]
        final = {e.get('eventType'): e for e in executions}
        ready = final.get('DATA_PREPARATION_READY', {})
        done = final.get('COMPUTE_COMPLETED', {})
        failed = [e for e in executions if any(word in str(e.get('eventType')) for word in ('FAILED', 'ERROR'))]
        if failed:
            state = '失败'
        elif done:
            state = '已完成'
        elif executions:
            state = '未完成'
        else:
            state = '缺少执行记录'
        result.append({'mode': mode, 'datasetId': dataset_id, 'placement': placement,
                       'ready': ready, 'done': done, 'state': state, 'failed': failed,
                       'transferStart': final.get('DATA_PREPARATION_STARTED', {}).get('occurredAt'),
                       'computeStart': final.get('COMPUTE_STARTED', {}).get('occurredAt')})
    return task, result


def checksum_state(group, context):
    ready = group['ready']
    checksum = ready.get('checksumSha256')
    if not isinstance(checksum, str) or not re.fullmatch(r'[0-9a-fA-F]{64}', checksum):
        return '缺少校验记录'
    dataset = context.get('datasets', {}).get(group['datasetId'], {})
    expected = dataset.get('authoritativeSha256')
    expected_size = dataset.get('authoritativeSizeBytes')
    if expected and str(checksum).lower() != str(expected).lower():
        return '校验不一致'
    if numeric(expected_size) and numeric(ready.get('bytesProcessed')) and expected_size != ready['bytesProcessed']:
        return '大小不一致'
    return '与登记值一致' if expected else '已记录 SHA-256'


def span(start, end, task_start):
    if start and end and task_start and str(start)[:10] == str(end)[:10] == str(task_start)[:10]:
        return '{} → {}'.format(str(start)[11:], str(end)[11:])
    return '{} → {}'.format(text(start), text(end))


def task_view(data, context):
    task, groups = task_groups(data)
    mode = task.get('executionMode')
    title = {'CENTRALIZED': 'TC-4.2.1 集中式计算', 'IN_PLACE': 'TC-4.2.2 原位计算'}.get(mode, '计算任务')
    lines = ['{}  |  任务 {}  |  {}'.format(title, task.get('taskId', '—'), text(task.get('status'))),
             '时间：{} → {}    执行证据：{}'.format(text(task.get('startedAt')), text(task.get('finishedAt')),
                  '完整' if task.get('executionEvidenceComplete') is True else '不完整')]
    if task.get('runtimeImageId') is not None:
        lines.append('任务模板镜像：{}{}'.format(task['runtimeImageId'],
                     '    资源配置：' + task['resourceOverridesJson'] if task.get('resourceOverridesJson') else ''))
    rows, timing, notes = [], [], []
    for g in groups:
        p, ready, done = g['placement'], g['ready'], g['done']
        name = dataset_label(g['datasetId'], context)
        if mode == 'COMPARISON':
            name += ' / ' + MODES.get(g['mode'], str(g['mode']))
        actual = done.get('nodeName') or ready.get('nodeName')
        target = p.get('targetNode')
        rows.append([name, p.get('sourceNode'), target, actual, g['state'], checksum_state(g, context)])
        timing.append([name, size_text(ready.get('bytesProcessed')),
                       seconds(ready['durationMs'] / 1000) if numeric(ready.get('durationMs')) else '—',
                       span(g['transferStart'], ready.get('occurredAt'), task.get('startedAt')),
                       span(g['computeStart'], done.get('occurredAt'), task.get('startedAt'))])
        if target and actual and actual != target:
            notes.append('{}：计划节点与实际执行节点不一致。'.format(name))
        reason = p.get('reason') or ''
        if any(word in reason for word in ('不足', '回退', '不可用', '失败')):
            notes.append('{}：{}'.format(name, reason.rsplit('；', 1)[-1]))
        for failure in g['failed']:
            notes.append('{}：{}'.format(name, failure.get('outputSummary') or details(failure).get('reason') or failure.get('eventType')))
    lines.extend(table(['数据集', '数据源', '计划节点', '实际节点', '执行状态', '输入校验'], rows,
                       [22, 16, 16, 16, 16, 16]))
    lines.append('数据移动与计算记录（耗时单位：秒；时间沿用后端记录）：')
    lines.extend(table(['数据集', '读取量', '移动耗时', '传输起止', '计算起止'], timing,
                       [22, 12, 10, 27, 27]))
    if not groups:
        lines.append('缺少逐数据集执行记录，无法核对实际执行节点和完成状态。')
    if notes:
        lines.append('需关注：')
        for note in dict.fromkeys(notes):
            lines.extend('  ' + part for part in wrap(note, 108))
    return lines


def speedup_record(data, context):
    task, groups = task_groups(data)
    reasons = []
    if task.get('executionMode') != 'COMPARISON':
        reasons.append('非两种模式对比任务')
    expected_ids = decoded(task.get('datasetIdsJson'), [])
    if not expected_ids:
        reasons.append('缺少任务数据集清单')
    if task.get('status') not in ('已完成', 'COMPLETED') or task.get('executionEvidenceComplete') is not True:
        reasons.append('任务未完成或执行证据不完整')
    pairs = {}
    totals = {'CENTRALIZED': 0, 'IN_PLACE': 0}
    for g in groups:
        pairs.setdefault(g['datasetId'], {})[g['mode']] = g
        if g['mode'] not in totals:
            reasons.append('存在未知计算模式')
        if expected_ids and g['datasetId'] not in [str(i) for i in expected_ids]:
            reasons.append('执行数据集与任务选择不一致')
        duration = g['ready'].get('durationMs')
        if g['state'] != '已完成' or not numeric(duration) or duration < 0:
            reasons.append('逐数据集执行或计时缺失')
        elif g['mode'] in totals:
            totals[g['mode']] += duration / 1000
        if checksum_state(g, context) in ('校验不一致', '大小不一致', '缺少校验记录'):
            reasons.append('输入校验不完整或不一致')
        if not numeric(g['ready'].get('bytesProcessed')) or g['ready']['bytesProcessed'] < 0:
            reasons.append('缺少有效读取量')
        if not g['transferStart'] or not g['ready'].get('occurredAt') or not g['done'].get('occurredAt'):
            reasons.append('执行起止时间缺失')
    if not pairs:
        reasons.append('缺少逐数据集记录')
    for pair in pairs.values():
        if not all(m in pair for m in totals):
            reasons.append('缺少一种计算模式')
            continue
        left, right = (pair[m]['ready'] for m in ('CENTRALIZED', 'IN_PLACE'))
        if (not left.get('checksumSha256') or not right.get('checksumSha256') or
                str(left.get('checksumSha256')).lower() != str(right.get('checksumSha256')).lower() or
                left.get('bytesProcessed') != right.get('bytesProcessed')):
            reasons.append('两种模式输入不一致')
    central, inplace = task.get('t2'), task.get('t1')
    if not numeric(central) or central < 0 or not numeric(inplace) or inplace <= 0:
        reasons.append('集中式计算计时无效或原位计算计时不大于零')
    for m, recorded in [('CENTRALIZED', central), ('IN_PLACE', inplace)]:
        if numeric(recorded) and abs(totals[m] - recorded) > 0.0011:
            reasons.append('累计计时与任务记录不一致')
    ratio = central / inplace if numeric(central) and numeric(inplace) and central >= 0 and inplace > 0 else None
    shown = task.get('rating')
    if not numeric(shown):
        reasons.append('页面加速比未记录')
    if ratio is not None and numeric(shown) and abs(ratio - shown) > 0.00051:
        reasons.append('页面加速比与复算值不一致')
    return task, groups, list(dict.fromkeys(reasons)), ratio


def task_ids(values):
    numbers = sorted(set(values))
    runs, current = [], []
    for value in numbers:
        if current and value != current[-1] + 1:
            runs.append(current)
            current = []
        current.append(value)
    if current:
        runs.append(current)
    return ','.join('{}–{}'.format(run[0], run[-1]) if len(run) >= 3 else ','.join(map(str, run)) for run in runs)


def speedup_view(records, context):
    lines = ['TC-4.2.5 数据移动加速比', 'T1：集中式计算数据移动时间；T2：原位计算数据移动时间；S = T1 / T2；要求 S > 1.2。']
    rows, routes, timing, notes = [], {}, [], []
    for data in records:
        task, groups, reasons, ratio = speedup_record(data, context)
        names = list(dict.fromkeys(dataset_label(g['datasetId'], context) for g in groups))
        rows.append([task.get('taskId'), '、'.join(names) or task.get('selectedData'),
                     seconds(task.get('t2')), seconds(task.get('t1')),
                     '{:.3f}'.format(ratio) if ratio is not None else '—',
                     '不纳入判定' if reasons else '达到要求' if ratio > 1.2 else '未达要求'])
        pairs = {}
        for g in groups:
            pairs.setdefault(g['datasetId'], {})[g['mode']] = g
        for dataset_id, pair in pairs.items():
            central, inplace = pair.get('CENTRALIZED', {}), pair.get('IN_PLACE', {})
            def actual(g):
                return g.get('done', {}).get('nodeName') or g.get('ready', {}).get('nodeName')
            left, right = central.get('placement', {}).get('sourceNode'), inplace.get('placement', {}).get('sourceNode')
            source = left if left == right else '集:{} / 分:{}'.format(text(left), text(right))
            route = (dataset_label(dataset_id, context), source, actual(central), actual(inplace))
            routes.setdefault(route, []).append(task.get('taskId'))
            if len(pairs) > 1:
                durations = []
                for group in (central, inplace):
                    ms = group.get('ready', {}).get('durationMs')
                    durations.append(seconds(ms / 1000) if numeric(ms) else '—')
                timing.append([task.get('taskId'), dataset_label(dataset_id, context)] + durations)
        if reasons:
            notes.append('任务 {}：{}。'.format(task.get('taskId'), '；'.join(reasons)))
    lines.extend(table(['任务', '数据集', 'T1 / 秒', 'T2 / 秒', 'S（复算）', '判定'], rows, [8, 27, 12, 12, 12, 16]))
    lines.append('实际调度路径（相同路径合并列出任务编号）：')
    paths = [[task_ids(ids)] + list(route) for route, ids in routes.items()]
    lines.extend(table(['任务', '数据集', '数据源', '集中式计算节点', '原位计算节点'], paths, [20, 22, 28, 18, 18]))
    if timing:
        lines.append('多数据集任务的累计计时依据：')
        lines.extend(table(['任务', '数据集', '集中式计算 / 秒', '原位计算 / 秒'], timing))
    for note in notes:
        lines.extend(wrap(note, 112))
    return lines


def dataset_view(records, context, heat=False):
    policy = context.get('policy') or {}
    threshold = policy.get('heatThreshold')
    lines = ['TC-4.2.3 温度与当前存储位置' if heat else '数据集与副本状态']
    if heat:
        lines.append('热敏存储温度阈值：{}    以下为当前观测值。'.format(text(threshold)))
    rows = []
    for d in records:
        replicas = d.get('replicas') or []
        nodes = list(dict.fromkeys(node_label(r.get('nodeId'), context) for r in replicas))
        temp = d.get('dataHeat')
        eligible = '达到阈值' if numeric(temp) and numeric(threshold) and temp >= threshold else '低于阈值' if numeric(temp) and numeric(threshold) else '—'
        common = [d.get('datasetId'), d.get('name'), '{:.2f}'.format(temp) if numeric(temp) else '—']
        if heat:
            rows.append(common + [eligible, '、'.join(nodes), d.get('heatUpdatedAt'),
                                 '{}/{}'.format(d.get('availableReplicaCount', '—'), d.get('totalReplicaCount', len(replicas)))])
        else:
            rows.append(common + [size_text(d.get('authoritativeSizeBytes')), '、'.join(nodes),
                                 text(d.get('healthStatus') or d.get('status'))])
    if heat:
        lines.extend(table(['ID', '数据集', '温度', '阈值对照', '当前存储节点', '温度更新时间', '可用/总副本'], rows,
                           [5, 20, 8, 10, 26, 24, 12]))
    else:
        lines.extend(table(['ID', '数据集', '温度', '大小', '当前存储节点', '状态'], rows, [5, 28, 8, 12, 30, 12]))
    return lines


def plan_view(data, context):
    plan = data.get('plan') or {}
    lines = ['{}  |  计划 {}  |  {}'.format(plan.get('algorithmName') or '存储调度', plan.get('planId', '—'), text(plan.get('status'))),
             '时间：{} → {}'.format(text(plan.get('createdAt')), text(plan.get('updatedAt')))]
    rows = [[dataset_label(a.get('datasetId'), context), a.get('sourceNodeName') or node_label(a.get('sourceNodeId'), context),
             a.get('targetNodeName') or node_label(a.get('targetNodeId'), context), text(a.get('action')), text(a.get('status'))]
            for a in data.get('assignments') or []]
    lines.extend(table(['数据集', '源节点', '目标节点', '操作', '执行状态'], rows))
    for a in data.get('assignments') or []:
        if a.get('errorMessage'):
            lines.append('失败原因：' + a['errorMessage'])
    if plan.get('errorMessage'):
        lines.append('计划错误：' + plan['errorMessage'])
    lines.append('本表为计划执行记录；当前数据位置以数据集查询为准。')
    return lines


def access_view(data, context):
    me = context.get('me') or {}
    lines = ['TC-4.2.4 当前访问权限  |  用户：{}'.format(me.get('username', '身份信息未取得'))]
    if 'ADMIN' in (me.get('roles') or []):
        lines.append('当前为管理员身份；受限用户结果需使用该用户会话查询。')
    has_grants = any(d.get('grantId') is not None for d in items(data))
    rows = [[d.get('datasetId'), d.get('name'), '、'.join(d.get('domainNames') or []),
             '允许' if d.get('accessible') is True else '拒绝' if d.get('accessible') is False else '未提供',
             d.get('grantId'), d.get('grantExpiresAt')] for d in items(data)]
    headers, limits = ['ID', '数据集', '所属域', '当前访问', '授权编号', '授权到期时间'], [5, 30, 18, 10, 8, 25]
    if not has_grants:
        rows, headers, limits = [row[:4] for row in rows], headers[:4], limits[:4]
    lines.extend(table(headers, rows, limits))
    return lines


def other_view(command, data, context):
    rows = items(data)
    if command == 'audit':
        out = ['TC-4.2.4 访问审计']
        out.extend(table(['事件', '用户', '数据集', '操作', '结果', '原因', '时间'],
            [[r.get('eventId'), r.get('principal'), dataset_label(r.get('datasetId'), context),
              r.get('action'), text(r.get('decision')), text(r.get('reason')), r.get('createdAt')] for r in rows],
            [7, 15, 20, 16, 6, 26, 19]))
        return out
    if command == 'grants':
        return ['TC-4.2.4 短期授权记录'] + table(['授权', '用户', '数据集', '生效时间', '到期时间', '当前状态'],
            [[r.get('grantId'), r.get('username'), r.get('datasetName'), r.get('createdAt'), r.get('expiresAt'),
              '生效' if r.get('active') is True else '已失效' if r.get('active') is False else '未知'] for r in rows],
            [7, 15, 22, 25, 25, 10])
    if command in ('nodes', 'topology'):
        return ['计算与存储节点'] + table(['ID', '节点', '角色', '站点', '登记状态', '观测状态'],
            [[r.get('nodeId'), r.get('k8sNodeName') or r.get('nodeName'), text(r.get('role') or str(r.get('type', '')).upper()),
              r.get('siteCode'), text(r.get('registrationStatus')), text(r.get('observedStatus'))] for r in rows])
    if command == 'replicas':
        return ['当前存储副本'] + table(['数据集', '副本', '节点', '大小', '可用性', '校验状态'],
            [[dataset_label(r.get('datasetId'), context), r.get('replicaId'), node_label(r.get('nodeId'), context),
              size_text(r.get('sizeBytes')), text(r.get('effectiveAvailability') or r.get('availability')),
              '已记录 SHA-256' if r.get('checksum') else '缺少校验记录'] for r in rows],
            [25, 7, 18, 12, 12, 20])
    if command == 'policy':
        return ['热敏存储条件', '  温度阈值：{}    最低时延改善：{} 毫秒    当前可执行：{}'.format(
            text(data.get('heatThreshold')), text(data.get('minLatencyGainMs')), text(data.get('heatEnabled')))] + (
            ['  限制原因：' + data['heatReason']] if data.get('heatReason') else [])
    if command == 'me':
        return ['当前用户：{}    所属域：{}    角色：{}'.format(text(data.get('username')), text(data.get('domainName')),
                '、'.join(data.get('roles') or []))]
    if command == 'reads':
        return ['数据集有效读取记录'] + table(['任务', '数据集', '读取量', '缓存命中', '成功', '完成时间'],
            [[r.get('taskId'), dataset_label(r.get('datasetId'), context), size_text(r.get('bytesRead')),
              size_text(r.get('cacheHitBytes')), text(r.get('success')), r.get('completedAt')] for r in rows])
    if command == 'tasks':
        return ['任务列表'] + table(['任务', '数据集', '模式', '状态', '开始时间', '结束时间'],
            [[r.get('taskId'), r.get('selectedData'), MODES.get(r.get('executionMode'), r.get('executionMode')),
              r.get('status'), r.get('startedAt'), r.get('finishedAt')] for r in rows], [7, 27, 16, 10, 19, 19])
    if command == 'plans':
        return ['存储计划列表'] + table(['计划', '算法', '状态', '开始时间', '结束时间'],
            [[r.get('planId'), r.get('algorithmName'), r.get('status'), r.get('createdAt'), r.get('updatedAt')] for r in rows])
    if command == 'comparison':
        return ['两种模式对比', '  集中式计算任务：{}    原位计算任务：{}    满足对比条件：{}'.format(
            text(data.get('centralizedTaskId')), text(data.get('inPlaceTaskId')), text(data.get('comparable'))),
            '  集中式计算耗时：{} 秒    原位计算耗时：{} 秒    加速比：{}'.format(
                seconds(data['centralizedPreparationMs'] / 1000) if numeric(data.get('centralizedPreparationMs')) else '—',
                seconds(data['inPlacePreparationMs'] / 1000) if numeric(data.get('inPlacePreparationMs')) else '—',
                text(data.get('centralizedToInPlaceRatio')))] + (['  原因：' + data['reason']] if data.get('reason') else [])
    return ['该查询暂无测试摘要，请去掉 -h 查看完整记录。']


def human_responses(records, command, context):
    good, errors = [], []
    for path, status, payload in records:
        if not isinstance(payload, dict) or payload.get('code') != 0 or not 200 <= status < 300:
            errors.append('查询失败：{}  HTTP {}  {}'.format(path, status, payload.get('msg', '响应格式异常') if isinstance(payload, dict) else '响应格式异常'))
        else:
            good.append(payload.get('data'))
    blocks = []
    if command in ('speedup', 'tc5') and good:
        blocks.append(speedup_view(good, context))
    elif command in ('dataset', 'heat', 'tc3', 'datasets') and good:
        ds = [d for data in good for d in items(data)] if command == 'datasets' else good
        blocks.append(dataset_view(ds, context, heat=command in ('heat', 'tc3')))
    else:
        for data in good:
            if command in ('task', 'tc1', 'tc2'):
                blocks.append(task_view(data, context))
            elif command == 'plan':
                blocks.append(plan_view(data, context))
            elif command in ('access', 'tc4'):
                blocks.append(access_view(data, context))
            else:
                blocks.append(other_view(command, data, context))
    for data in good:
        if isinstance(data, dict) and data.get('pageNum') is not None:
            blocks.append(['第 {} 页 / 每页 {} 条；总计 {} 条。'.format(data.get('pageNum'), data.get('pageSize'), data.get('total'))])
    lines = []
    for block in blocks:
        if lines:
            lines.append('')
        lines.extend(block)
    lines.extend(errors)
    if context.get('warnings'):
        lines.append('辅助信息未取得：{}；缺少的名称或校验依据以编号或“缺少”显示。'.format('；'.join(context['warnings'])))
    return '\n'.join(lines)

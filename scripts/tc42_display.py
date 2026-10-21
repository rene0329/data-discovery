"""Human-readable views for TC-4.2 responses, compatible with Python 3.6."""
import json
import math


LABELS = {
    'code': '响应码', 'msg': '响应说明', 'errorCode': '错误代码',
    'data': '查询结果', 'traceId': '请求追踪编号', 'timestamp': '响应时间戳',
    'task': '任务', 'taskId': '任务编号', 'taskName': '任务名称',
    'selectedData': '参与数据集', 'datasetIdsJson': '数据集编号',
    'runtimeImageId': '计算镜像编号', 'resourceOverridesJson': '资源配置',
    'executionMode': '计算模式', 'acceptanceRunId': '测试运行标识',
    'runRound': '测试轮次', 'status': '状态', 'createTime': '创建时间',
    'startedAt': '开始时间', 'finishedAt': '结束时间',
    'executionEvidenceComplete': '执行证据完整', 'schedule': '调度方案',
    'manualSchedule': '手动调度方案', 'dataPreparationMs': '数据准备耗时',
    'computeDurationMs': '计算耗时', 't1': '分布式数据移动时间（t1）',
    't2': '集中式数据移动时间（t2）', 'rating': '数据移动加速比',
    'events': '执行事件', 'eventId': '事件编号', 'eventType': '事件类型',
    'occurredAt': '发生时间', 'jobName': '执行作业', 'podName': '执行容器组',
    'nodeName': '节点名称', 'inputPath': '输入路径', 'bytesProcessed': '处理数据量',
    'checksumSha256': '输入 SHA-256', 'outputChecksumSha256': '输出 SHA-256',
    'outputSummary': '执行输出', 'durationMs': '耗时', 'attempt': '尝试次数',
    'detailsJson': '详细记录', 'datasetId': '数据集编号', 'datasetCode': '数据集代码',
    'datasetName': '数据集名称', 'name': '名称', 'version': '版本',
    'datasetVersion': '数据集版本', 'description': '说明', 'dataType': '数据类型',
    'category': '类别', 'format': '文件格式', 'dataHeat': '数据温度',
    'heatUpdatedAt': '温度更新时间', 'domainIds': '所属域编号',
    'domainNames': '所属域', 'replicas': '存储副本', 'replicaId': '副本编号',
    'nodeId': '节点编号', 'filePath': '文件路径', 'sizeBytes': '文件大小',
    'checksumAlgorithm': '校验算法', 'checksum': '校验值',
    'availability': '可用状态', 'effectiveAvailability': '实际可用状态',
    'verificationMessage': '校验说明', 'verifiedAt': '校验时间',
    'createdAt': '创建时间', 'updatedAt': '更新时间', 'lastSeenAt': '最近发现时间',
    'healthStatus': '健康状态', 'availableReplicaCount': '可用副本数',
    'totalReplicaCount': '副本总数', 'statusReason': '状态说明',
    'authoritativeSha256': '登记的 SHA-256', 'authoritativeSizeBytes': '登记的文件大小',
    'plan': '调度计划', 'planId': '计划编号', 'externalPlanId': '外部计划编号',
    'internalTaskId': '内部任务编号', 'algorithmName': '算法名称',
    'algorithmVersion': '算法版本', 'errorMessage': '错误信息',
    'assignments': '数据分配记录', 'assignmentId': '分配编号',
    'sourceNodeId': '源节点编号', 'sourceNodeName': '源节点',
    'targetNodeId': '目标节点编号', 'targetNodeName': '目标节点',
    'sourceNode': '源节点', 'targetNode': '目标节点', 'action': '操作',
    'heatThreshold': '热敏存储温度阈值', 'heatEnabled': '热敏存储可用',
    'aggregationEnabled': '原位汇聚可用', 'heatReason': '热敏存储说明',
    'aggregationReason': '原位汇聚说明', 'minLatencyGainMs': '最低时延改善量',
    'taskCount': '任务总数', 'unfinishedTaskCount': '未完成任务数',
    'items': '记录', 'list': '记录', 'total': '总记录数',
    'pageNum': '当前页', 'pageSize': '每页记录数', 'serverTime': '服务端时间',
    'ttlMinutes': '授权有效期', 'accessible': '允许访问', 'basis': '授权依据',
    'grantId': '授权编号', 'grantExpiresAt': '授权到期时间',
    'grantReason': '授权说明', 'expiresAt': '到期时间', 'active': '当前生效',
    'userId': '用户编号', 'id': '编号', 'username': '用户名',
    'displayName': '显示名称', 'domain': '所属域', 'domainId': '所属域编号',
    'domainName': '所属域名称', 'roles': '角色', 'impersonated': '处于身份切换',
    'actorUserId': '操作管理员编号', 'actorUsername': '操作管理员',
    'principal': '访问主体', 'requestId': '请求编号', 'runId': '运行标识',
    'decision': '访问决定', 'reason': '原因', 'policyRule': '访问策略',
    'tokenExpiresAt': '令牌到期时间', 'clientIp': '客户端地址',
    'clusterId': '集群编号', 'cluster': '集群', 'k8sNodeName': '集群节点名称',
    'role': '节点角色', 'type': '节点类型', 'siteCode': '站点代码',
    'registrationStatus': '登记状态', 'observedStatus': '观测状态',
    'effectiveStatus': '实际状态', 'enabled': '启用', 'schedulable': '允许调度',
    'internalIp': '内网地址', 'externalIp': '外网地址',
    'maxMemoryGi': '内存容量', 'currentMemoryGi': '当前内存用量',
    'maxCpu': 'CPU 容量', 'currentCpu': '当前 CPU 用量',
    'labels': '标签', 'labelsJson': '标签', 'requiredResources': '资源要求',
    'bytesRead': '读取数据量', 'cacheHitBytes': '缓存命中数据量',
    'bytesTotal': '总数据量', 'bytesDone': '已完成数据量',
    'centralizedPreparationMs': '集中式数据准备耗时',
    'inPlacePreparationMs': '分布式数据准备耗时',
    'centralizedToInPlaceRatio': '集中式与分布式耗时比', 'comparable': '满足对比条件',
    'centralizedTaskId': '集中式任务编号', 'inPlaceTaskId': '分布式任务编号',
}

MODES = {'CENTRALIZED': '集中式计算', 'IN_PLACE': '分布式计算', 'COMPARISON': '两种模式对比'}
STATUSES = {'COMPLETED': '已完成', 'RUNNING': '运行中', 'ACCEPTED': '已受理',
            'FAILED': '失败', 'AVAILABLE': '可用', 'USABLE': '可用', 'ACTIVE': '已启用',
            'ONLINE': '在线', 'OFFLINE': '离线', 'HEALTHY': '正常',
            'ALLOWED': '允许', 'DENIED': '拒绝'}
BYTE_FIELDS = {'sizeBytes', 'authoritativeSizeBytes', 'bytesProcessed', 'bytesRead',
               'cacheHitBytes', 'bytesTotal', 'bytesDone', 'authoritative_size_bytes'}


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def size_text(value):
    if value < 0:
        return '{} B（负值，待核对）'.format(value)
    units = ('B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB')
    size, index = value, 0
    while size >= 1024 and index < len(units) - 1:
        size /= 1024.0
        index += 1
    return '{:g} B'.format(size) if index == 0 else '{:.2f} {}'.format(size, units[index])


def duration_text(seconds):
    if seconds < 0:
        return '{} 秒（负值，待核对）'.format(seconds)
    if seconds == 0:
        return '0 秒'
    if seconds < 1:
        return '{:g} 毫秒'.format(seconds * 1000)
    if seconds < 60:
        return '{:.3f} 秒'.format(seconds)
    # Round first so carry from 59.9999 seconds is reflected in the minutes.
    millis = round(seconds * 1000)
    hours, remainder = divmod(millis, 3600000)
    minutes, remainder = divmod(remainder, 60000)
    result = '{} 分 {:.3f} 秒'.format(minutes, remainder / 1000)
    return '{} 小时 {}'.format(hours, result) if hours else result


def scalar_text(key, value):
    if value is None:
        return '未提供'
    if isinstance(value, bool):
        return '是' if value else '否'
    if number(value):
        if key in BYTE_FIELDS or key.endswith('Bytes'):
            return size_text(value)
        if key.endswith('MemoryGi') or key in ('memoryGi', 'memoryRequestedGi', 'memoryFreeGi'):
            return size_text(value * 1024 ** 3)
        if key in ('t1', 't2'):
            return duration_text(value)
        if key.endswith('Ms'):
            return duration_text(value / 1000.0)
        if key == 'ttlMinutes':
            return '{} 分钟'.format(value)
        if key in ('rating', 'centralizedToInPlaceRatio'):
            return '{:.3f}'.format(value)
    if isinstance(value, str):
        meaning = MODES.get(value) if key == 'executionMode' else None
        if key in ('status', 'availability', 'effectiveAvailability', 'effectiveStatus',
                   'registrationStatus', 'observedStatus', 'healthStatus', 'decision'):
            meaning = STATUSES.get(value)
        if meaning:
            return '{}（{}）'.format(meaning, value)
    return str(value)


def render_value(key, value, indent=0):
    """Retain every returned field and record, including unknown future fields."""
    prefix = ' ' * indent
    label = LABELS.get(key, key)
    if key.endswith('Json') and isinstance(value, str):
        try:
            value = json.loads(value)
        except ValueError:
            pass
    if isinstance(value, dict):
        if not value:
            return [prefix + label + '：无记录']
        lines = [prefix + label + '：']
        for child, item in value.items():
            lines.extend(render_value(child, item, indent + 2))
        return lines
    if isinstance(value, list):
        if not value:
            return [prefix + label + '：无记录']
        if all(not isinstance(item, (dict, list)) for item in value):
            return [prefix + label + '：' + '、'.join(scalar_text(key, item) for item in value)]
        lines = [prefix + '{}（{} 条）：'.format(label, len(value))]
        for index, item in enumerate(value, 1):
            lines.extend(render_value('第 {} 条'.format(index), item, indent + 2))
        return lines
    text = scalar_text(key, value)
    if '\n' in text:
        return [prefix + label + '：'] + [prefix + '  ' + line for line in text.splitlines()]
    return [prefix + label + '：' + text]


def human_response(payload, path, status):
    lines = ['查询：{}  HTTP {}'.format(path, status)]
    lines.extend(render_value('响应', payload))
    return '\n'.join(lines)

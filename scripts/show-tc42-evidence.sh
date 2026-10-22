#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only command-line views for the TC-4.2 acceptance items. The script
# reads Kubernetes and MySQL state; it does not create, update, or delete data.

umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="${TOPIC4_NAMESPACE:-topic4-1}"
MYSQL_WORKLOAD="${TOPIC4_MYSQL_WORKLOAD:-deployment/mysql}"
MYSQL_SECRET="${TOPIC4_MYSQL_SECRET:-project4-mysql-secret}"
MYSQL_SECRET_KEY="${TOPIC4_MYSQL_SECRET_KEY:-root-password}"
MYSQL_USER="${TOPIC4_MYSQL_USER:-root}"
MYSQL_DATABASE="${TOPIC4_MYSQL_DATABASE:-Project4}"
LOG_SINCE="${LOG_SINCE:-30m}"
DB_PASSWORD=""
SNAPSHOT_DIR="${TC42_SNAPSHOT_DIR:-/data/topic4-build/evidence/tc42-baselines}"
SCHEDULING_LOG_PATTERN='异步任务|【热度】|TASK_PLACEMENT_|TASK_JOB_|TASK_TRANSFER_|JOB_TARGET_ASSIGNED|SCHEDULING_|STORAGE_PLACEMENT_|智能调度流程|候选节点|评分:|决策阶段完成|调度决策被覆盖|分布式执行|原位执行|跨站点回退|准备Job:|传输时间|单模式执行结束|对比任务 .*执行结束|任务 [0-9]+ 开始执行'

usage() {
  cat <<'EOF'
用法：
  show-tc42-evidence.sh snapshot <dataset_id ...>   # 前端创建任务前保存副本快照
  show-tc42-evidence.sh report <task_id>            # 用例1/2/5统一入口
  show-tc42-evidence.sh baseline [dataset_id ...]
  show-tc42-evidence.sh task <task_id> [--log-since <时长>] [--no-logs]
  show-tc42-evidence.sh decision <task_id> [--selected]
  show-tc42-evidence.sh plan <plan_id> [show-scheduling-evidence.sh 选项]
  show-tc42-evidence.sh heat <dataset_id ...>
  show-tc42-evidence.sh access <principal> <dataset_id> [task_id]
  show-tc42-evidence.sh speedup <task_id ...>
  show-tc42-evidence.sh logs [--since <时长>] [--follow]

示例：
  ./scripts/show-tc42-evidence.sh snapshot 1 4 6
  ./scripts/show-tc42-evidence.sh report 228
  ./scripts/show-tc42-evidence.sh baseline 1 4 6
  ./scripts/show-tc42-evidence.sh task 224 --log-since 1h
  ./scripts/show-tc42-evidence.sh decision 226
  ./scripts/show-tc42-evidence.sh decision 226 --selected
  ./scripts/show-tc42-evidence.sh plan 64 --wait-seconds 1800 --log-since 1h
  ./scripts/show-tc42-evidence.sh heat 7 9
  ./scripts/show-tc42-evidence.sh access owner-a 3 92
  ./scripts/show-tc42-evidence.sh speedup 219 220 221 222 223
  ./scripts/show-tc42-evidence.sh logs --since 1m --follow

可选环境变量：
  TOPIC4_NAMESPACE、TOPIC4_MYSQL_WORKLOAD、TOPIC4_MYSQL_SECRET、
  TOPIC4_MYSQL_SECRET_KEY、TOPIC4_MYSQL_USER、TOPIC4_MYSQL_DATABASE、
  LOG_SINCE、TC42_SNAPSHOT_DIR（测试前快照保存目录）

退出码：
  0  查询执行完成
  1  参数或运行环境错误
  2  指定记录不存在
EOF
}

fail() {
  echo "错误：$*" >&2
  exit 1
}

require_positive_integer() {
  local label="$1"
  local value="$2"
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] || fail "${label} 必须是正整数"
}

require_duration() {
  local value="$1"
  [[ "${value}" =~ ^[1-9][0-9]*[smhd]$ ]] \
    || fail "日志时长应类似 30m、1h 或 2d"
}

join_integer_ids() {
  local result=""
  local item
  (($# > 0)) || fail "至少需要一个编号"
  for item in "$@"; do
    require_positive_integer "编号" "${item}"
    if [[ -n "${result}" ]]; then
      result+=","
    fi
    result+="${item}"
  done
  printf '%s' "${result}"
}

cleanup() {
  unset DB_PASSWORD
}
trap cleanup EXIT

backend_init() {
  local command_name
  for command_name in kubectl base64 date hostname; do
    command -v "${command_name}" >/dev/null 2>&1 || fail "缺少命令：${command_name}"
  done

  kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1 \
    || fail "无法访问 Kubernetes 命名空间 ${NAMESPACE}"
  kubectl get -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" >/dev/null 2>&1 \
    || fail "找不到 MySQL 工作负载 ${NAMESPACE}/${MYSQL_WORKLOAD}"

  DB_PASSWORD="$(kubectl get secret -n "${NAMESPACE}" "${MYSQL_SECRET}" \
    -o "jsonpath={.data.${MYSQL_SECRET_KEY}}" | base64 -d)"
  [[ -n "${DB_PASSWORD}" ]] || fail "无法读取数据库密码"
}

mysql_raw() {
  kubectl exec -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" -- \
    env MYSQL_PWD="${DB_PASSWORD}" \
    mysql --connect-timeout=5 --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -D"${MYSQL_DATABASE}" \
      --batch --raw --skip-column-names \
      --init-command="SET time_zone='+00:00'" -e "$1"
}

mysql_xml() {
  kubectl exec -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" -- \
    env MYSQL_PWD="${DB_PASSWORD}" \
    mysql --connect-timeout=5 --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -D"${MYSQL_DATABASE}" --xml \
      --init-command="SET time_zone='+00:00'" -e "$1"
}

mysql_table() {
  if [[ "${TC42_TABLE_LAYOUT:-raw}" == compact ]]; then
    mysql_xml "$1" | PYTHONIOENCODING=utf-8 python3 -B "$SCRIPT_DIR/tc42_table.py" --width "${TC42_TABLE_WIDTH:-120}" | python3 -u "${SCRIPT_DIR}/computation_terms.py"
    return
  fi
  kubectl exec -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" -- \
    env MYSQL_PWD="${DB_PASSWORD}" \
    mysql --connect-timeout=5 --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -D"${MYSQL_DATABASE}" --table \
      --init-command="SET time_zone='+00:00'" -e "$1" | python3 -u "${SCRIPT_DIR}/computation_terms.py"
}

mysql_vertical() {
  if [[ "${TC42_TABLE_LAYOUT:-raw}" == compact ]]; then
    mysql_xml "$1" | PYTHONIOENCODING=utf-8 python3 -B "$SCRIPT_DIR/tc42_table.py" --width "${TC42_TABLE_WIDTH:-120}" --vertical | python3 -u "${SCRIPT_DIR}/computation_terms.py"
    return
  fi
  kubectl exec -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" -- \
    env MYSQL_PWD="${DB_PASSWORD}" \
    mysql --connect-timeout=5 --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -D"${MYSQL_DATABASE}" --vertical \
      --init-command="SET time_zone='+00:00'" -e "$1" | python3 -u "${SCRIPT_DIR}/computation_terms.py"
}

print_metadata() {
  echo "[backend.metadata]"
  echo "query_time=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "host=$(hostname)"
  echo "kubernetes_context=$(kubectl config current-context)"
  echo "namespace=${NAMESPACE}"
}

show_baseline() {
  local filter=""
  if (($# > 0)); then
    local dataset_ids
    dataset_ids="$(join_integer_ids "$@")"
    filter="AND d.dataset_id IN (${dataset_ids})"
  fi

  print_metadata
  echo
  echo "[backend.dataset_replicas]"
  mysql_table "
SELECT
  d.dataset_id,
  d.dataset_code,
  d.name,
  d.dataset_version,
  r.replica_id,
  r.size_bytes,
  n.node_id,
  n.node_name,
  n.type AS node_type,
  n.site_code,
  r.availability,
  r.checksum_algorithm,
  r.checksum,
  r.verified_at
FROM registered_dataset d
JOIN dataset_replica r ON r.dataset_id = d.dataset_id
JOIN node_management n ON n.node_id = r.node_id
WHERE d.deleted_at IS NULL
  ${filter}
ORDER BY d.dataset_id, r.replica_id;" || return $?

  [[ "${BASELINE_REPLICAS_ONLY:-0}" == "1" ]] && return 0
  echo
  echo "[backend.nodes]"
  mysql_table "
SELECT
  node_id,
  node_name,
  type,
  cluster,
  site_code,
  max_cpu,
  current_cpu,
  max_memory,
  current_memory,
  registration_status,
  observed_status,
  last_seen_at
FROM node_management
WHERE deleted_at IS NULL
ORDER BY node_id;"

  echo
  echo "[backend.network_links]"
  mysql_table "
SELECT
  source.node_name AS source_node,
  target.node_name AS target_node,
  link.latency AS latency_ms,
  link.bandwidth AS bandwidth_mbps,
  link.status,
  link.measurement_time
FROM node_link_metric link
JOIN node_management source ON source.node_id = link.source_id
JOIN node_management target ON target.node_id = link.target_id
ORDER BY source.node_name, target.node_name;"
}

show_task_logs() {
  local task_id="$1"
  local log_since="$2"
  local window
  local started_at
  local finished_at
  local logs
  local job_names

  window="$(mysql_raw "
SELECT
  DATE_FORMAT(COALESCE(started_at, create_time), '%Y-%m-%dT%H:%i:%s'),
  DATE_FORMAT(COALESCE(finished_at, UTC_TIMESTAMP(3)), '%Y-%m-%dT%H:%i:%s')
FROM task_management
WHERE task_id = ${task_id};")"
  read -r started_at finished_at <<<"${window}"
  job_names="$(mysql_raw "SELECT DISTINCT job_name FROM task_execution_event
                         WHERE task_id=${task_id} AND job_name IS NOT NULL;")"

  echo
  echo "[backend.practice_server_scheduling_logs]"
  echo "task_id=${task_id} log_since=${log_since} window_utc=${started_at}/${finished_at}"

  logs="$({
    kubectl logs -n "${NAMESPACE}" -l app=practice-server \
      --all-containers=true --prefix=true --max-log-requests=20 \
      --tail=-1 --since="${log_since}" 2>/dev/null || true
  } | awk -v start="${started_at}" -v end="${finished_at}" -v id="${task_id}" \
          -v jobs="${job_names}" -v pattern="${SCHEDULING_LOG_PATTERN}" '
      BEGIN { count = split(jobs, names, "\n") }
      {
        gsub(/\033\[[0-9;]*[mK]/, "")
        timestamp = $2 "T" substr($3, 1, 8)
        if (timestamp < start || timestamp > end) next
        belongs = $0 ~ ("taskId=" id "([^0-9]|$)") || $0 ~ ("任务 " id "([^0-9]|$)")
        for (i = 1; i <= count && !belongs; i++) {
          if (names[i] != "" && index($0, names[i]) > 0) belongs = 1
        }
        if (belongs && $0 ~ pattern) print
      }' | tail -n 200 | python3 -u "${SCRIPT_DIR}/computation_terms.py")"

  if [[ -n "${logs}" ]]; then
    printf '%s\n' "${logs}"
    echo "scheduling_log_count=$(printf '%s\n' "${logs}" | wc -l | tr -d ' ')"
  else
    echo "scheduling_log_count=0"
  fi
}

show_placement_events() {
  local task_id="$1"
  local selected_filter=""
  if [[ "${2:-0}" == "1" ]]; then
    selected_filter="AND event_type='PLACEMENT_SELECTED'"
  fi
  echo
  echo "[backend.task_placement_events]"
  mysql_raw "
SELECT CONCAT(
  DATE_FORMAT(occurred_at, '%Y-%m-%d %H:%i:%s.%f'), ' ', event_type,
  ' task_id=', task_id, ' dataset_id=', dataset_id, ' mode=', execution_mode,
  CHAR(10), '  tier=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.tier')), 'null'),
  ' outcome=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.outcome')), 'null'),
  ' replica=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.replicaId')), 'null'),
  ' source=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.sourceNode')), 'null'),
  ' target=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.targetNode')), 'null'),
  ' source_site=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.sourceSite')), 'null'),
  ' target_site=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.targetSite')), 'null'),
  CHAR(10), '  cpu_requested=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.cpuRequested')), 'null'),
  ' cpu_free=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.cpuFree')), 'null'),
  ' memory_requested_gi=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.memoryRequestedGi')), 'null'),
  ' memory_free_gi=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.memoryFreeGi')), 'null'),
  ' resource_check=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.resourceCheck')), 'null'),
  CHAR(10), '  latency_ms=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.latencyMs')), 'null'),
  ' bandwidth_mbps=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.bandwidthMbps')), 'null'),
  ' path_node_ids=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.pathNodeIds')), 'null'),
  CHAR(10), '  reason=', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.reason')), 'null')
)
FROM task_execution_event
WHERE task_id=${task_id} AND event_type LIKE 'PLACEMENT_%'
  ${selected_filter}
ORDER BY event_id;"
  echo "placement_event_count=$(mysql_raw "SELECT COUNT(*) FROM task_execution_event
    WHERE task_id=${task_id} AND event_type LIKE 'PLACEMENT_%' ${selected_filter};")"
}

show_decision() {
  (($# == 1 || $# == 2)) || fail "decision 需要 task_id，可选 --selected"
  require_positive_integer "task_id" "$1"
  local selected_only=0
  if (($# == 2)); then
    [[ "$2" == "--selected" ]] || fail "decision 的未知选项：$2"
    selected_only=1
  fi
  if [[ "$(mysql_raw "SELECT COUNT(*) FROM task_management WHERE task_id=$1;")" != "1" ]]; then
    echo "task_id=$1 record_count=0"
    exit 2
  fi
  print_metadata
  show_placement_events "$1" "${selected_only}"
}

show_task() {
  local task_id=""
  local include_logs=1
  local log_since="${LOG_SINCE}"

  (($# > 0)) || fail "task 缺少 task_id"
  task_id="$1"
  require_positive_integer "task_id" "${task_id}"
  shift

  while (($# > 0)); do
    case "$1" in
      --log-since)
        (($# >= 2)) || fail "--log-since 缺少参数"
        log_since="$2"
        require_duration "${log_since}"
        shift 2
        ;;
      --no-logs)
        include_logs=0
        shift
        ;;
      *)
        fail "task 的未知选项：$1"
        ;;
    esac
  done

  [[ "$(mysql_raw "SELECT COUNT(*) FROM task_management WHERE task_id=${task_id};")" == "1" ]] \
    || exit 2

  print_metadata
  echo "task_id=${task_id}"

  echo
  echo "========== 二、任务后端执行结果 =========="
  echo "说明：核对任务编号、模式、数据集、完成状态及执行证据；时间沿用数据库 UTC 记录。"
  echo "data_preparation_ms 为数据准备阶段跨度，不等同于逐数据集传输耗时之和；NULL 表示未记录。"
  echo "[backend.task]"
  mysql_vertical "
SELECT
  task_id,
  task_name,
  selected_data,
  dataset_ids_json,
  execution_mode,
  acceptance_run_id,
  run_round,
  status,
  create_time,
  started_at,
  finished_at,
  execution_evidence_complete,
  data_preparation_ms,
  compute_duration_ms,
  T1 AS database_T1,
  T2 AS database_T2,
  rating AS database_rating,
  CASE WHEN execution_mode = 'COMPARISON' THEN T2 END AS document_T1_centralized_seconds,
  CASE WHEN execution_mode = 'COMPARISON' THEN T1 END AS document_T2_in_place_seconds,
  CASE WHEN execution_mode = 'COMPARISON' THEN rating END AS document_speedup_T1_over_T2,
  schedule
FROM task_management
WHERE task_id = ${task_id};"

  echo
  echo "========== 三、任务调度方案与执行证据 =========="
  echo "说明：PLACEMENT_SELECTED 是最终选定的数据源和计算节点；下方事件核对实际执行节点及传输耗时。"
  show_placement_events "${task_id}"

  echo
  echo "[backend.task_execution_events]"
  mysql_table "
SELECT
  event_id,
  dataset_id,
  execution_mode,
  event_type,
  job_name,
  pod_name,
  node_name,
  bytes_processed,
  duration_ms,
  checksum_sha256,
  output_checksum_sha256,
  attempt,
  occurred_at
FROM task_execution_event
WHERE task_id = ${task_id}
  AND event_type NOT LIKE 'PLACEMENT_%'
ORDER BY event_id;"

  echo
  echo "========== 四、数据迁移状态与访问审计 =========="
  echo "4.1 数据迁移：核对源节点、目标节点、完成状态、重试及错误。NULL 不表示已完成校验。"
  echo "[backend.migration_tasks]"
  mysql_table "
SELECT
  migration.id AS migration_id,
  migration.registered_dataset_id AS dataset_id,
  source.node_name AS source_node,
  target.node_name AS target_node,
  migration.status,
  migration.retry_count,
  migration.bytes_total,
  migration.bytes_done,
  migration.checksum_before,
  migration.checksum_after,
  migration.started_at,
  migration.finished_at,
  migration.error_message
FROM migration_task migration
JOIN node_management source ON source.node_id = migration.source_node_id
JOIN node_management target ON target.node_id = migration.target_node_id
WHERE migration.task_id = ${task_id}
ORDER BY migration.id;"

  echo
  echo "4.2 访问审计：TASK_REQUEST 为本任务请求；RUN_CONTEXT 为同轮次辅助记录，不作为本任务的独立执行证明。"
  echo "[backend.task_access_audit]"
  mysql_table "
SELECT
  event_id,
  request_id,
  run_id,
  principal,
  dataset_id,
  action,
  target_node,
  policy_rule,
  decision,
  reason,
  CASE WHEN request_id LIKE CONCAT('task-', ${task_id}, '-%')
       THEN 'TASK_REQUEST' ELSE 'RUN_CONTEXT' END AS evidence_scope,
  created_at
FROM dataset_access_audit_event
WHERE request_id LIKE CONCAT('task-', ${task_id}, '-%')
   OR (BINARY run_id = BINARY (
         SELECT acceptance_run_id FROM task_management WHERE task_id = ${task_id}
       ) AND (request_id IS NULL OR request_id NOT LIKE 'task-%'))
ORDER BY event_id;"

  if [[ "${include_logs}" == "1" ]]; then
    show_task_logs "${task_id}" "${log_since}"
  fi
}

show_heat() {
  local dataset_ids
  dataset_ids="$(join_integer_ids "$@")"

  print_metadata
  echo "dataset_ids=${dataset_ids}"

  echo
  echo "[backend.dataset_heat]"
  mysql_table "
SELECT
  dataset.dataset_id,
  dataset.dataset_code,
  dataset.name,
  dataset.dataset_version,
  dataset.data_heat,
  dataset.heat_updated_at,
  COUNT(event.event_id) AS completed_task_reads,
  MAX(event.occurred_at) AS last_task_read_at
FROM registered_dataset dataset
LEFT JOIN task_execution_event event
  ON event.dataset_id = dataset.dataset_id
 AND event.event_type = 'DATA_PREPARATION_READY'
WHERE dataset.dataset_id IN (${dataset_ids})
GROUP BY dataset.dataset_id, dataset.dataset_code, dataset.name,
         dataset.dataset_version, dataset.data_heat, dataset.heat_updated_at
ORDER BY dataset.dataset_id;"

  echo
  echo "[backend.dataset_heat_recent_task_reads]"
  mysql_table "
SELECT
  event.task_id,
  event.dataset_id,
  event.execution_mode,
  event.node_name,
  event.bytes_processed,
  event.duration_ms,
  event.checksum_sha256,
  event.occurred_at
FROM task_execution_event event
WHERE event.dataset_id IN (${dataset_ids})
  AND event.event_type = 'DATA_PREPARATION_READY'
ORDER BY event.event_id DESC
LIMIT 20;"

  echo
  echo "[backend.dataset_access_events]"
  mysql_table "
SELECT
  event_id,
  request_id,
  run_id,
  task_id,
  dataset_id,
  consumer_node_id,
  source_replica_id,
  source_node_id,
  bytes_read,
  cache_hit_bytes,
  checksum,
  duration_ms,
  success,
  completed_at
FROM dataset_access_event
WHERE dataset_id IN (${dataset_ids})
ORDER BY event_id DESC
LIMIT 20;"
}

show_access() {
  (($# == 2 || $# == 3)) || fail "access 需要 principal、dataset_id，可选 task_id"
  local principal="$1"
  local dataset_id="$2"
  local task_id="${3:-}"

  [[ "${principal}" =~ ^[A-Za-z0-9._@-]+$ ]] || fail "principal 包含不支持的字符"
  require_positive_integer "dataset_id" "${dataset_id}"
  if [[ -n "${task_id}" ]]; then
    require_positive_integer "task_id" "${task_id}"
  fi

  print_metadata
  echo "principal=${principal} dataset_id=${dataset_id}${task_id:+ task_id=${task_id}}"

  echo
  echo "[backend.access_audit]"
  mysql_table "
SELECT
  event_id,
  request_id,
  run_id,
  principal,
  dataset_id,
  dataset_version,
  action,
  target_node,
  policy_rule,
  decision,
  reason,
  token_expires_at,
  client_ip,
  created_at
FROM dataset_access_audit_event
WHERE principal = '${principal}'
  AND dataset_id = '${dataset_id}'
ORDER BY event_id DESC
LIMIT 50;"

  echo
  echo "[backend.access_grants]"
  mysql_table "
SELECT
  grant_row.grant_id,
  user_row.username,
  dataset.dataset_id,
  dataset.dataset_code,
  dataset.name,
  grant_row.reason,
  grant_row.created_at,
  grant_row.expires_at
FROM dataset_access_grant grant_row
JOIN app_user user_row ON user_row.user_id = grant_row.user_id
JOIN registered_dataset dataset ON dataset.dataset_id = grant_row.dataset_id
WHERE user_row.username = '${principal}'
  AND dataset.dataset_id = ${dataset_id}
ORDER BY grant_row.grant_id DESC;"

  if [[ -n "${task_id}" ]]; then
    echo
    echo "[backend.post_grant_task]"
    mysql_vertical "
SELECT
  task_id,
  task_name,
  selected_data,
  dataset_ids_json,
  execution_mode,
  acceptance_run_id,
  status,
  started_at,
  finished_at,
  execution_evidence_complete,
  schedule
FROM task_management
WHERE task_id = ${task_id};"

    echo
    echo "[backend.post_grant_task_events]"
    mysql_table "
SELECT
  event_id,
  dataset_id,
  execution_mode,
  event_type,
  node_name,
  bytes_processed,
  duration_ms,
  checksum_sha256,
  occurred_at
FROM task_execution_event
WHERE task_id = ${task_id}
ORDER BY event_id;"
  fi
}

show_speedup() {
  local task_ids
  task_ids="$(join_integer_ids "$@")"

  print_metadata
  echo "task_ids=${task_ids}"

  echo
  echo "[backend.speedup_task_values]"
  mysql_table "
SELECT
  task_id,
  acceptance_run_id,
  run_round,
  status,
  T2 AS T1_centralized_seconds,
  T1 AS T2_in_place_seconds,
  rating AS speedup_T1_over_T2,
  ROUND(T2 / NULLIF(T1, 0), 3) AS recalculated_speedup,
  execution_evidence_complete,
  started_at,
  finished_at
FROM task_management
WHERE task_id IN (${task_ids})
ORDER BY task_id;"

  echo
  echo "[backend.speedup_event_aggregation]"
  mysql_table "
SELECT
  task_id,
  execution_mode,
  ROUND(SUM(duration_ms) / 1000, 3) AS preparation_seconds,
  SUM(bytes_processed) AS bytes_processed,
  COUNT(*) AS dataset_count
FROM task_execution_event
WHERE task_id IN (${task_ids})
  AND event_type = 'DATA_PREPARATION_READY'
GROUP BY task_id, execution_mode
ORDER BY task_id, execution_mode;"

  echo
  echo "[backend.speedup_dataset_events]"
  mysql_table "
SELECT
  event.task_id,
  event.dataset_id,
  dataset.name AS dataset_name,
  event.execution_mode,
  event.node_name,
  event.bytes_processed,
  event.duration_ms,
  event.checksum_sha256,
  event.occurred_at
FROM task_execution_event event
LEFT JOIN registered_dataset dataset ON dataset.dataset_id = event.dataset_id
WHERE event.task_id IN (${task_ids})
  AND event.event_type = 'DATA_PREPARATION_READY'
ORDER BY event.task_id, event.dataset_id, event.execution_mode;"
}

show_live_logs() {
  local log_since="${LOG_SINCE}"
  local follow=0

  while (($# > 0)); do
    case "$1" in
      --since)
        (($# >= 2)) || fail "--since 缺少参数"
        log_since="$2"
        require_duration "${log_since}"
        shift 2
        ;;
      --follow)
        follow=1
        shift
        ;;
      *)
        fail "logs 的未知选项：$1"
        ;;
    esac
  done

  print_metadata
  echo "log_since=${log_since} follow=${follow}"
  echo
  echo "[backend.practice_server_scheduling_logs]"

  local -a args=(
    logs -n "${NAMESPACE}" -l app=practice-server
    --all-containers=true --prefix=true --max-log-requests=20
    --tail=-1 --since="${log_since}"
  )
  if [[ "${follow}" == "1" ]]; then
    args+=(--follow)
  fi

  if [[ "${follow}" == "1" ]]; then
    kubectl "${args[@]}" 2>&1 \
      | grep --line-buffered -E \
        "${SCHEDULING_LOG_PATTERN}" | python3 -u "${SCRIPT_DIR}/computation_terms.py"
  else
    kubectl "${args[@]}" 2>&1 \
      | grep -E \
        "${SCHEDULING_LOG_PATTERN}" | python3 -u "${SCRIPT_DIR}/computation_terms.py" \
      || true
  fi
}

show_snapshot() {
  local dataset_ids captured content
  dataset_ids="$(join_integer_ids "$@")"
  command -v python3 >/dev/null || fail "缺少 python3"
  content="$(BASELINE_REPLICAS_ONLY=1 show_baseline "$@")"
  captured="$(mysql_raw "SELECT UNIX_TIMESTAMP(UTC_TIMESTAMP(3));")"
  echo "========== 测试前数据副本快照 =========="
  printf '%s\n' "$content"
  echo "快照已保存："
  printf '%s\n' "$content" | PYTHONIOENCODING=utf-8 python3 -B "$SCRIPT_DIR/tc42_snapshot.py" \
    save "$SNAPSHOT_DIR" "$dataset_ids" "$captured"
  echo "下一步：在前端创建任务，获得任务ID后执行 ./scripts/show-tc42-evidence.sh report <任务ID>"
}

show_report() {
  (($# == 1)) || fail "report 只需一个任务ID"
  require_positive_integer "task_id" "$1"
  command -v python3 >/dev/null || fail "缺少 python3"
  local task_id="$1" metadata mode dataset_ids created
  metadata="$(mysql_raw "SELECT execution_mode, dataset_ids_json, UNIX_TIMESTAMP(create_time)
    FROM task_management WHERE task_id=${task_id};")"
  [[ -n "$metadata" ]] || { echo "任务 ${task_id} 不存在" >&2; return 2; }
  IFS=$'\t' read -r mode dataset_ids created <<< "$metadata"
  case "$mode" in
    CENTRALIZED) echo "测试用例一 · 集中式计算 · 任务 ${task_id}" ;;
    IN_PLACE) echo "测试用例二 · 原位计算 · 任务 ${task_id}" ;;
    COMPARISON) echo "测试用例五 · 集中式计算／原位计算对比 · 任务 ${task_id}" ;;
    *) fail "任务模式不支持：${mode}" ;;
  esac
  echo "只查询指定任务；本命令不会创建任务、移动数据或更新业务记录。"
  echo
  echo "========== 一、测试前数据副本记录 =========="
  PYTHONIOENCODING=utf-8 python3 -B "$SCRIPT_DIR/tc42_snapshot.py" \
    select "$SNAPSHOT_DIR" "$dataset_ids" "$created"
  show_task "$task_id" --no-logs
  if [[ "$mode" == COMPARISON ]]; then
    echo
    echo "========== 五、集中式计算／原位计算计时与数据移动加速比 =========="
    echo "T1=集中式计算数据移动时间，T2=原位计算数据移动时间，加速比=T1/T2；以下保留原始值，不代替人工验收结论。"
    show_speedup "$task_id"
  fi
}

main() {
COMMAND="${1:-}"
case "${COMMAND}" in
  -h|--help|help|"")
    usage
    exit 0
    ;;
  plan)
    shift
    (($# > 0)) || fail "plan 缺少 plan_id"
    exec "${SCRIPT_DIR}/show-scheduling-evidence.sh" "$@"
    ;;
  snapshot|report|baseline|task|decision|heat|access|speedup|logs)
    shift
    backend_init
    case "${COMMAND}" in
      snapshot) show_snapshot "$@" ;;
      report) show_report "$@" ;;
      baseline) show_baseline "$@" ;;
      task) show_task "$@" ;;
      decision) show_decision "$@" ;;
      heat) show_heat "$@" ;;
      access) show_access "$@" ;;
      speedup) show_speedup "$@" ;;
      logs) show_live_logs "$@" ;;
    esac
    ;;
  *)
    fail "未知子命令：${COMMAND}"
    ;;
esac

}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi

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

usage() {
  cat <<'EOF'
用法：
  show-tc42-evidence.sh baseline [dataset_id ...]
  show-tc42-evidence.sh task <task_id> [--log-since <时长>] [--no-logs]
  show-tc42-evidence.sh plan <plan_id> [show-scheduling-evidence.sh 选项]
  show-tc42-evidence.sh heat <dataset_id ...>
  show-tc42-evidence.sh access <principal> <dataset_id> [task_id]
  show-tc42-evidence.sh speedup <task_id ...>
  show-tc42-evidence.sh logs [--since <时长>] [--follow]

示例：
  ./scripts/show-tc42-evidence.sh baseline 1 4 6
  ./scripts/show-tc42-evidence.sh task 224 --log-since 1h
  ./scripts/show-tc42-evidence.sh plan 64 --wait-seconds 1800 --log-since 1h
  ./scripts/show-tc42-evidence.sh heat 7 9
  ./scripts/show-tc42-evidence.sh access owner-a 3 92
  ./scripts/show-tc42-evidence.sh speedup 219 220 221 222 223
  ./scripts/show-tc42-evidence.sh logs --since 1m --follow

可选环境变量：
  TOPIC4_NAMESPACE、TOPIC4_MYSQL_WORKLOAD、TOPIC4_MYSQL_SECRET、
  TOPIC4_MYSQL_SECRET_KEY、TOPIC4_MYSQL_USER、TOPIC4_MYSQL_DATABASE、
  LOG_SINCE

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

mysql_table() {
  kubectl exec -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" -- \
    env MYSQL_PWD="${DB_PASSWORD}" \
    mysql --connect-timeout=5 --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -D"${MYSQL_DATABASE}" --table \
      --init-command="SET time_zone='+00:00'" -e "$1"
}

mysql_vertical() {
  kubectl exec -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" -- \
    env MYSQL_PWD="${DB_PASSWORD}" \
    mysql --connect-timeout=5 --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -D"${MYSQL_DATABASE}" --vertical \
      --init-command="SET time_zone='+00:00'" -e "$1"
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
ORDER BY d.dataset_id, r.replica_id;"

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

  window="$(mysql_raw "
SELECT
  DATE_FORMAT(COALESCE(started_at, create_time), '%Y-%m-%dT%H:%i:%s'),
  DATE_FORMAT(COALESCE(finished_at, UTC_TIMESTAMP(3)), '%Y-%m-%dT%H:%i:%s')
FROM task_management
WHERE task_id = ${task_id};")"
  read -r started_at finished_at <<<"${window}"

  echo
  echo "[backend.practice_server_scheduling_logs]"
  echo "task_id=${task_id} log_since=${log_since} window_utc=${started_at}/${finished_at}"

  logs="$({
    kubectl logs -n "${NAMESPACE}" -l app=practice-server \
      --all-containers=true --prefix=true --max-log-requests=20 \
      --tail=-1 --since="${log_since}" 2>/dev/null || true
  } | awk -v start="${started_at}" -v end="${finished_at}" '
      {
        timestamp = $2 "T" $3
        if (timestamp < start || timestamp > end) next
        if ($0 ~ /TASK_PLACEMENT_|SCHEDULING_|STORAGE_PLACEMENT_|智能调度流程|候选节点|评分:|决策阶段完成|调度决策被覆盖|分布式执行|跨站点回退|准备Job:|传输时间|单模式执行结束|对比任务 .*执行结束|任务 [0-9]+ 开始执行/) print
      }' | tail -n 200)"

  if [[ -n "${logs}" ]]; then
    printf '%s\n' "${logs}"
    echo "scheduling_log_count=$(printf '%s\n' "${logs}" | wc -l | tr -d ' ')"
  else
    echo "scheduling_log_count=0"
  fi
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
ORDER BY event_id;"

  echo
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
  created_at
FROM dataset_access_audit_event
WHERE run_id = (SELECT acceptance_run_id FROM task_management WHERE task_id = ${task_id})
   OR request_id LIKE CONCAT('task-', ${task_id}, '-%')
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
  grant.grant_id,
  user.username,
  dataset.dataset_id,
  dataset.dataset_code,
  dataset.name,
  grant.reason,
  grant.created_at,
  grant.expires_at
FROM dataset_access_grant grant
JOIN app_user user ON user.user_id = grant.user_id
JOIN registered_dataset dataset ON dataset.dataset_id = grant.dataset_id
WHERE user.username = '${principal}'
  AND dataset.dataset_id = ${dataset_id}
ORDER BY grant.grant_id DESC;"

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
        'TASK_PLACEMENT_|SCHEDULING_|STORAGE_PLACEMENT_|智能调度流程|候选节点|评分:|决策阶段完成|调度决策被覆盖|分布式执行|跨站点回退|准备Job:|传输时间|单模式执行结束|对比任务 .*执行结束|任务 [0-9]+ 开始执行'
  else
    kubectl "${args[@]}" 2>&1 \
      | grep -E \
        'TASK_PLACEMENT_|SCHEDULING_|STORAGE_PLACEMENT_|智能调度流程|候选节点|评分:|决策阶段完成|调度决策被覆盖|分布式执行|跨站点回退|准备Job:|传输时间|单模式执行结束|对比任务 .*执行结束|任务 [0-9]+ 开始执行' \
      || true
  fi
}

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
  baseline|task|heat|access|speedup|logs)
    shift
    backend_init
    case "${COMMAND}" in
      baseline) show_baseline "$@" ;;
      task) show_task "$@" ;;
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

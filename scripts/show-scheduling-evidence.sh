#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only evidence viewer for Topic4 data scheduling. Run it on the ZJ build
# host after the scheduling API returns a plan_id. The script does not create,
# update, or delete any Kubernetes or database resource.

umask 077

NAMESPACE="${TOPIC4_NAMESPACE:-topic4-1}"
MYSQL_WORKLOAD="${TOPIC4_MYSQL_WORKLOAD:-deployment/mysql}"
MYSQL_SECRET="${TOPIC4_MYSQL_SECRET:-project4-mysql-secret}"
MYSQL_SECRET_KEY="${TOPIC4_MYSQL_SECRET_KEY:-root-password}"
MYSQL_USER="${TOPIC4_MYSQL_USER:-root}"
MYSQL_DATABASE="${TOPIC4_MYSQL_DATABASE:-Project4}"
WAIT_SECONDS="${WAIT_SECONDS:-600}"
POLL_SECONDS="${POLL_SECONDS:-2}"
LOG_SINCE="${LOG_SINCE:-30m}"

usage() {
  cat <<'EOF'
用法：
  show-scheduling-evidence.sh <plan_id> [选项]

选项：
  --wait-seconds <秒>  等待计划进入终态的最长时间，默认 600
  --poll-seconds <秒>  等待期间的查询间隔，默认 2
  --log-since <时长>   节点容器日志查询范围，默认 30m
  --no-wait            不等待，立即展示当前状态
  -h, --help           显示帮助

示例：
  ./scripts/show-scheduling-evidence.sh 64
  ./scripts/show-scheduling-evidence.sh 64 --wait-seconds 1800 --log-since 1h

可选环境变量：
  TOPIC4_NAMESPACE、TOPIC4_MYSQL_WORKLOAD、TOPIC4_MYSQL_SECRET、
  TOPIC4_MYSQL_SECRET_KEY、TOPIC4_MYSQL_USER、TOPIC4_MYSQL_DATABASE

退出码：
  0  所有分配项均有后端执行和目标文件校验证据
  1  参数或运行环境错误
  2  计划不存在
  3  等待超时
  4  计划失败、部分完成或后端证据不完整
EOF
}

fail() {
  echo "错误：$*" >&2
  exit 1
}

is_non_negative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

PLAN_ID=""
while (($# > 0)); do
  case "$1" in
    --wait-seconds)
      (($# >= 2)) || fail "--wait-seconds 缺少参数"
      WAIT_SECONDS="$2"
      shift 2
      ;;
    --poll-seconds)
      (($# >= 2)) || fail "--poll-seconds 缺少参数"
      POLL_SECONDS="$2"
      shift 2
      ;;
    --log-since)
      (($# >= 2)) || fail "--log-since 缺少参数"
      LOG_SINCE="$2"
      shift 2
      ;;
    --no-wait)
      WAIT_SECONDS=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --*)
      fail "未知选项：$1"
      ;;
    *)
      [[ -z "${PLAN_ID}" ]] || fail "只能指定一个 plan_id"
      PLAN_ID="$1"
      shift
      ;;
  esac
done

[[ -n "${PLAN_ID}" ]] || {
  usage >&2
  exit 1
}
[[ "${PLAN_ID}" =~ ^[1-9][0-9]*$ ]] || fail "plan_id 必须是正整数"
is_non_negative_integer "${WAIT_SECONDS}" || fail "等待秒数必须是非负整数"
[[ "${POLL_SECONDS}" =~ ^[1-9][0-9]*$ ]] || fail "查询间隔必须是正整数"
[[ "${LOG_SINCE}" =~ ^[1-9][0-9]*[smhd]$ ]] || fail "日志时长应类似 30m、1h 或 2d"

for command_name in kubectl base64 date grep tail; do
  command -v "${command_name}" >/dev/null 2>&1 || fail "缺少命令：${command_name}"
done

DB_PASSWORD=""
cleanup() {
  unset DB_PASSWORD
}
trap cleanup EXIT

kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1 \
  || fail "无法访问 Kubernetes 命名空间 ${NAMESPACE}"
kubectl get -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" >/dev/null 2>&1 \
  || fail "找不到 MySQL 工作负载 ${NAMESPACE}/${MYSQL_WORKLOAD}"

DB_PASSWORD="$(kubectl get secret -n "${NAMESPACE}" "${MYSQL_SECRET}" \
  -o "jsonpath={.data.${MYSQL_SECRET_KEY}}" | base64 -d)"
[[ -n "${DB_PASSWORD}" ]] || fail "无法读取数据库密码"

mysql_raw() {
  kubectl exec -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" -- \
    env MYSQL_PWD="${DB_PASSWORD}" \
    mysql --connect-timeout=5 --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -D"${MYSQL_DATABASE}" \
      --batch --raw --skip-column-names -e "$1"
}

mysql_table() {
  kubectl exec -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" -- \
    env MYSQL_PWD="${DB_PASSWORD}" \
    mysql --connect-timeout=5 --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -D"${MYSQL_DATABASE}" --table -e "$1"
}

mysql_vertical() {
  kubectl exec -n "${NAMESPACE}" "${MYSQL_WORKLOAD}" -- \
    env MYSQL_PWD="${DB_PASSWORD}" \
    mysql --connect-timeout=5 --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -D"${MYSQL_DATABASE}" --vertical -e "$1"
}

plan_status() {
  mysql_raw "SELECT status FROM scheduling_plan WHERE plan_id=${PLAN_ID};"
}

echo "Topic4 后端调度执行证据"
echo "plan_id=${PLAN_ID} namespace=${NAMESPACE} 查询时间=$(date '+%Y-%m-%d %H:%M:%S %Z')"
echo

STATUS="$(plan_status)"
if [[ -z "${STATUS}" ]]; then
  echo "未找到 plan_id=${PLAN_ID}" >&2
  exit 2
fi

TIMED_OUT=0
if ((WAIT_SECONDS > 0)); then
  DEADLINE=$(( $(date +%s) + WAIT_SECONDS ))
  while [[ "${STATUS}" == "ACCEPTED" || "${STATUS}" == "RUNNING" ]]; do
    NOW="$(date +%s)"
    if ((NOW >= DEADLINE)); then
      TIMED_OUT=1
      break
    fi
    printf '等待调度完成：status=%s，剩余最多 %s 秒\r' \
      "${STATUS}" "$((DEADLINE - NOW))"
    sleep "${POLL_SECONDS}"
    STATUS="$(plan_status)"
  done
  printf '%*s\r' 100 ''
fi

echo "1. 调度计划、分配项与目标文件证据"
mysql_vertical "
SELECT
  CASE
    WHEN p.status <> 'COMPLETED' OR a.status <> 'COMPLETED'
      THEN 'NOT_COMPLETED'
    WHEN a.action IN ('COPY', 'MOVE') AND EXISTS (
      SELECT 1
      FROM dataset_replica r
      JOIN dataset_discovery_candidate c
        ON c.node_id = r.node_id AND c.file_path = r.file_path
      WHERE r.dataset_id = a.dataset_id
        AND r.node_id = a.target_node_id
        AND r.availability = 'AVAILABLE'
        AND UPPER(r.checksum_algorithm) = 'SHA-256'
        AND r.checksum REGEXP '^[0-9A-Fa-f]{64}$'
        AND r.verified_at >= p.created_at
        AND c.availability = 'AVAILABLE'
        AND UPPER(c.checksum_algorithm) = 'SHA-256'
        AND c.checksum = r.checksum
        AND c.last_seen_at >= p.updated_at
    ) THEN 'REAL_ACTION_CONFIRMED'
    WHEN a.action = 'DELETE' AND EXISTS (
      SELECT 1
      FROM dataset_access_audit_event e
      WHERE e.request_id = CONCAT('storage-plan-', p.plan_id,
                                  '-assignment-', a.assignment_id)
        AND e.action = 'DELETE'
        AND e.decision = 'ALLOWED'
    ) THEN 'DELETE_RECORDED_CHECK_NODE_LOG'
    ELSE 'EVIDENCE_INCOMPLETE'
  END AS backend_evidence,
  p.plan_id,
  p.external_plan_id,
  p.status AS plan_status,
  p.error_message AS plan_error,
  p.created_at AS plan_created_at_utc,
  p.updated_at AS plan_updated_at_utc,
  a.assignment_id,
  a.dataset_id,
  CONCAT(a.source_node_id, '/', COALESCE(src.node_name, '?')) AS source_node,
  CONCAT(a.target_node_id, '/', COALESCE(dst.node_name, '?')) AS target_node,
  a.action,
  a.status AS assignment_status,
  a.error_message AS assignment_error,
  r.replica_id AS target_replica_id,
  r.file_path AS target_file,
  r.size_bytes,
  r.checksum_algorithm,
  r.checksum,
  r.availability,
  r.verified_at AS target_verified_at_utc,
  c.last_seen_at AS target_agent_last_seen_utc
FROM scheduling_plan p
JOIN scheduling_assignment a ON a.plan_id = p.plan_id
LEFT JOIN node_management src ON src.node_id = a.source_node_id
LEFT JOIN node_management dst ON dst.node_id = a.target_node_id
LEFT JOIN dataset_replica r
  ON r.replica_id = (
    SELECT r2.replica_id
    FROM dataset_replica r2
    WHERE r2.dataset_id = a.dataset_id
      AND r2.node_id = a.target_node_id
    ORDER BY (r2.verified_at >= p.created_at) DESC,
             r2.verified_at DESC,
             r2.replica_id DESC
    LIMIT 1
  )
LEFT JOIN dataset_discovery_candidate c
  ON c.node_id = r.node_id AND c.file_path = r.file_path
WHERE p.plan_id = ${PLAN_ID}
ORDER BY a.assignment_id;"

echo
echo "2. 后端授权与数据动作审计轨迹"
AUDIT_COUNT="$(mysql_raw "
SELECT COUNT(*)
FROM dataset_access_audit_event
WHERE request_id LIKE 'storage-plan-${PLAN_ID}-assignment-%';")"
if [[ "${AUDIT_COUNT}" == "0" ]]; then
  echo "未找到该计划的数据动作审计记录。"
else
  mysql_table "
SELECT event_id, request_id, dataset_id, action, target_node,
       decision, reason, file_path, created_at AS created_at_utc
FROM dataset_access_audit_event
WHERE request_id LIKE 'storage-plan-${PLAN_ID}-assignment-%'
ORDER BY event_id;"
fi

echo
echo "3. 数据节点最近的物理复制或删除日志（${LOG_SINCE}）"
PLAN_FILE_PATHS="$(mysql_raw "
SELECT DISTINCT file_path
FROM dataset_access_audit_event
WHERE request_id LIKE 'storage-plan-${PLAN_ID}-assignment-%'
  AND file_path IS NOT NULL
  AND file_path <> '';")"
RECENT_ACTION_LOGS=""
if [[ -n "${PLAN_FILE_PATHS}" ]]; then
  RECENT_ACTION_LOGS="$(
    kubectl logs -n "${NAMESPACE}" -l app=data-discovery \
      --all-containers=true --prefix=true --max-log-requests=50 \
      --since="${LOG_SINCE}" 2>/dev/null \
      | grep -E '节点间流式复制(成功|失败)|删除文件:|文件上传(成功|失败)' \
      | grep -F -f <(printf '%s\n' "${PLAN_FILE_PATHS}") \
      | tail -n 30 || true
  )"
fi
if [[ -n "${RECENT_ACTION_LOGS}" ]]; then
  printf '%s\n' "${RECENT_ACTION_LOGS}"
else
  echo "当前 Pod 日志范围内没有物理复制或删除记录；数据库证据仍显示在上方。"
fi

read -r TOTAL_ASSIGNMENTS CONFIRMED_ASSIGNMENTS DELETE_ASSIGNMENTS <<<"$(mysql_raw "
SELECT
  COUNT(*) AS total_assignments,
  COALESCE(SUM(CASE WHEN a.status = 'COMPLETED'
            AND a.action IN ('COPY', 'MOVE')
            AND EXISTS (
              SELECT 1
              FROM dataset_replica r
              JOIN dataset_discovery_candidate c
                ON c.node_id = r.node_id AND c.file_path = r.file_path
              WHERE r.dataset_id = a.dataset_id
                AND r.node_id = a.target_node_id
                AND r.availability = 'AVAILABLE'
                AND UPPER(r.checksum_algorithm) = 'SHA-256'
                AND r.checksum REGEXP '^[0-9A-Fa-f]{64}$'
                AND r.verified_at >= p.created_at
                AND c.availability = 'AVAILABLE'
                AND UPPER(c.checksum_algorithm) = 'SHA-256'
                AND c.checksum = r.checksum
                AND c.last_seen_at >= p.updated_at
            ) THEN 1 ELSE 0 END), 0) AS confirmed_assignments,
  COALESCE(SUM(CASE WHEN a.action = 'DELETE' THEN 1 ELSE 0 END), 0) AS delete_assignments
FROM scheduling_plan p
JOIN scheduling_assignment a ON a.plan_id = p.plan_id
WHERE p.plan_id = ${PLAN_ID};")"

echo
if ((TIMED_OUT == 1)); then
  echo "结论：等待超时，当前计划状态为 ${STATUS}。" >&2
  exit 3
fi
if [[ "${STATUS}" != "COMPLETED" ]]; then
  echo "结论：计划终态为 ${STATUS}，未形成完整成功证据。" >&2
  exit 4
fi
if ((DELETE_ASSIGNMENTS > 0)); then
  echo "结论：计划包含 DELETE；请以上方节点删除日志作为物理删除证据。" >&2
  exit 4
fi
if [[ "${TOTAL_ASSIGNMENTS}" == "${CONFIRMED_ASSIGNMENTS}" && "${TOTAL_ASSIGNMENTS}" != "0" ]]; then
  echo "结论：REAL_ACTION_CONFIRMED（${CONFIRMED_ASSIGNMENTS}/${TOTAL_ASSIGNMENTS} 个分配项）"
  echo "调度状态、目标副本、SHA-256 和目标节点扫描记录相互一致。"
  exit 0
fi

echo "结论：EVIDENCE_INCOMPLETE（已确认 ${CONFIRMED_ASSIGNMENTS}/${TOTAL_ASSIGNMENTS} 个分配项）。" >&2
echo "不要仅凭前端页面或 scheduling_plan 状态宣称数据动作完成。" >&2
exit 4

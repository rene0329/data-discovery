#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only backend state viewer for Topic4 data scheduling. Run it on the ZJ build
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
LIVE_FILE_CHECK="${LIVE_FILE_CHECK:-1}"

usage() {
  cat <<'EOF'
用法：
  show-scheduling-evidence.sh <plan_id> [选项]

选项：
  --wait-seconds <秒>  等待计划进入终态的最长时间，默认 600
  --poll-seconds <秒>  等待期间的查询间隔，默认 2
  --log-since <时长>   节点容器日志查询范围，默认 30m
  --no-live-check      不进入目标节点计算文件大小和 SHA-256
  --no-wait            不等待，立即展示当前状态
  -h, --help           显示帮助

示例：
  ./scripts/show-scheduling-evidence.sh 64
  ./scripts/show-scheduling-evidence.sh 64 --wait-seconds 1800 --log-since 1h

可选环境变量：
  TOPIC4_NAMESPACE、TOPIC4_MYSQL_WORKLOAD、TOPIC4_MYSQL_SECRET、
  TOPIC4_MYSQL_SECRET_KEY、TOPIC4_MYSQL_USER、TOPIC4_MYSQL_DATABASE

退出码：
  0  后端记录与目标文件校验一致
  1  参数或运行环境错误
  2  计划不存在
  3  等待超时
  4  计划未完成，或后端记录与目标文件校验不一致
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
    --no-live-check)
      LIVE_FILE_CHECK=0
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

plan_status() {
  mysql_raw "SELECT status FROM scheduling_plan WHERE plan_id=${PLAN_ID};"
}

database_match_counts() {
  mysql_raw "
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
                AND r.verified_at <= p.updated_at + INTERVAL 10 MINUTE
                AND c.availability = 'AVAILABLE'
                AND UPPER(c.checksum_algorithm) = 'SHA-256'
                AND c.checksum = r.checksum
                AND c.last_seen_at >= p.updated_at
            ) THEN 1 ELSE 0 END), 0) AS confirmed_assignments,
  COALESCE(SUM(CASE WHEN a.action = 'DELETE' THEN 1 ELSE 0 END), 0) AS delete_assignments
FROM scheduling_plan p
JOIN scheduling_assignment a ON a.plan_id = p.plan_id
WHERE p.plan_id = ${PLAN_ID};"
}

echo "Topic4 后端调度信息"
echo "plan_id=${PLAN_ID} namespace=${NAMESPACE} query_time=$(date '+%Y-%m-%d %H:%M:%S %Z')"
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
    printf 'plan_status=%s wait_remaining_seconds=%s\r' \
      "${STATUS}" "$((DEADLINE - NOW))"
    sleep "${POLL_SECONDS}"
    STATUS="$(plan_status)"
  done
  printf '%*s\r' 100 ''

  if [[ "${STATUS}" == "COMPLETED" && "${TIMED_OUT}" == "0" ]]; then
    while true; do
      read -r WAIT_TOTAL WAIT_CONFIRMED WAIT_DELETE <<<"$(database_match_counts)"
      if [[ "${WAIT_TOTAL}" != "0" && "${WAIT_TOTAL}" == "${WAIT_CONFIRMED}" ]]; then
        break
      fi
      # DELETE is proved by its node-side log and absence rather than a target
      # replica, so do not wait for the COPY/MOVE evidence predicate.
      if ((WAIT_DELETE > 0)); then
        break
      fi
      NOW="$(date +%s)"
      if ((NOW >= DEADLINE)); then
        TIMED_OUT=1
        break
      fi
      printf 'database_match_assignments=%s assignment_total=%s wait_remaining_seconds=%s\r' \
        "${WAIT_CONFIRMED}" "${WAIT_TOTAL}" "$((DEADLINE - NOW))"
      sleep "${POLL_SECONDS}"
    done
    printf '%*s\r' 120 ''
  fi
fi

echo "1. scheduling_plan、scheduling_assignment、dataset_replica"
mysql_vertical "
SELECT
  p.plan_id,
  p.external_plan_id,
  p.status AS plan_status,
  p.error_message AS plan_error,
  p.algorithm_name,
  p.algorithm_version,
  p.task_id,
  p.internal_task_id,
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
echo "2. dataset_access_audit_event"
AUDIT_COUNT="$(mysql_raw "
SELECT COUNT(*)
FROM dataset_access_audit_event
WHERE request_id LIKE 'storage-plan-${PLAN_ID}-assignment-%';")"
if [[ "${AUDIT_COUNT}" == "0" ]]; then
  echo "audit_event_count=0"
else
  mysql_table "
SELECT event_id, request_id, dataset_id, action, target_node,
       decision, reason, file_path, created_at AS created_at_utc
FROM dataset_access_audit_event
WHERE request_id LIKE 'storage-plan-${PLAN_ID}-assignment-%'
ORDER BY event_id;"
fi

echo
echo "3. practice-server 调度日志（log_since=${LOG_SINCE}）"
PLAN_FILE_PATHS="$(mysql_raw "
SELECT DISTINCT file_path
FROM dataset_access_audit_event
WHERE request_id LIKE 'storage-plan-${PLAN_ID}-assignment-%'
  AND file_path IS NOT NULL
  AND file_path <> '';")"
IFS=$'\t' read -r PLAN_LOG_START PLAN_LOG_END <<<"$(mysql_raw "
SELECT
  DATE_FORMAT(DATE_SUB(created_at, INTERVAL 2 MINUTE), '%Y-%m-%dT%H:%i:%s'),
  DATE_FORMAT(DATE_ADD(updated_at, INTERVAL 2 MINUTE), '%Y-%m-%dT%H:%i:%s')
FROM scheduling_plan
WHERE plan_id = ${PLAN_ID};")"
SCHEDULING_LOGS="$(
  kubectl logs -n "${NAMESPACE}" -l app=practice-server \
    --all-containers=true --prefix=true --max-log-requests=20 \
    --tail=-1 --since="${LOG_SINCE}" 2>/dev/null \
    | awk -v start="${PLAN_LOG_START}" -v end="${PLAN_LOG_END}" '
        {
          timestamp = $2 "T" $3
          in_window = timestamp >= start && timestamp <= end
          if (!in_window) next
          if ($0 ~ /SCHEDULING_|STORAGE_PLACEMENT_|scheduling_plan|scheduling_assignment|DatasetSchedulingExecutor|SchedulingService|智能调度流程|调度方案/) {
            print
            remaining = 2
            next
          }
          if (remaining > 0) {
            print
            remaining--
          }
        }' \
    | tail -n 100 || true
)"
if [[ -n "${SCHEDULING_LOGS}" ]]; then
  printf '%s\n' "${SCHEDULING_LOGS}"
else
  echo "scheduling_log_count=0"
fi

echo
echo "4. data-discovery Pod 日志（log_since=${LOG_SINCE}）"
RECENT_ACTION_LOGS=""
if [[ -n "${PLAN_FILE_PATHS}" ]]; then
  RECENT_ACTION_LOGS="$(
    kubectl logs -n "${NAMESPACE}" -l app=data-discovery \
      --all-containers=true --prefix=true --max-log-requests=50 \
      --tail=-1 --since="${LOG_SINCE}" 2>/dev/null \
      | awk -v start="${PLAN_LOG_START}" -v end="${PLAN_LOG_END}" \
          '{ timestamp = $2 "T" $3; if (timestamp >= start && timestamp <= end) print }' \
      | grep -E '节点间流式复制(成功|失败)|删除文件:|文件上传(成功|失败)' \
      | grep -F -f <(printf '%s\n' "${PLAN_FILE_PATHS}") \
      | tail -n 30 || true
  )"
fi
if [[ -n "${RECENT_ACTION_LOGS}" ]]; then
  printf '%s\n' "${RECENT_ACTION_LOGS}"
else
  echo "physical_action_log_count=0"
fi

echo
echo "5. 目标节点文件状态"
LIVE_TOTAL=0
LIVE_CONFIRMED=0
if [[ "${LIVE_FILE_CHECK}" == "1" ]]; then
  while IFS=$'\t' read -r ASSIGNMENT_ID ACTION TARGET_NODE TARGET_FILE \
      EXPECTED_SIZE EXPECTED_SHA256 PLAN_CREATED_EPOCH PLAN_UPDATED_EPOCH; do
    [[ -n "${ASSIGNMENT_ID}" ]] || continue
    LIVE_TOTAL=$((LIVE_TOTAL + 1))
    echo "assignment_id=${ASSIGNMENT_ID} action=${ACTION} target_node=${TARGET_NODE}"
    echo "target_file=${TARGET_FILE}"

    TARGET_POD="$(kubectl get pods -n "${NAMESPACE}" -l app=data-discovery \
      --field-selector "spec.nodeName=${TARGET_NODE}" \
      -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
    if [[ -z "${TARGET_POD}" ]]; then
      echo "live_file_check_status=TARGET_AGENT_POD_NOT_FOUND"
      echo
      continue
    fi

    LIVE_OUTPUT=""
    if ! LIVE_OUTPUT="$(kubectl exec -n "${NAMESPACE}" "${TARGET_POD}" -- \
      sh -c '
        file_path="$1"
        actual_size=$(wc -c <"$file_path")
        modified_epoch=$(stat -c %Y "$file_path")
        checksum_line=$(sha256sum "$file_path")
        actual_sha256=${checksum_line%% *}
        printf "%s\t%s\t%s\n" "$actual_size" "$actual_sha256" "$modified_epoch"
      ' sh "${TARGET_FILE}" 2>/dev/null)"; then
      echo "target_pod=${TARGET_POD}"
      echo "live_file_check_status=TARGET_FILE_CHECK_FAILED"
      echo
      continue
    fi

    IFS=$'\t' read -r ACTUAL_SIZE ACTUAL_SHA256 MODIFIED_EPOCH <<<"${LIVE_OUTPUT}"
    echo "target_pod=${TARGET_POD}"
    echo "actual_size=${ACTUAL_SIZE} expected_size=${EXPECTED_SIZE}"
    echo "actual_sha256=${ACTUAL_SHA256}"
    echo "expected_sha256=${EXPECTED_SHA256}"
    echo "file_modified_epoch=${MODIFIED_EPOCH} plan_window_epoch=${PLAN_CREATED_EPOCH}-${PLAN_UPDATED_EPOCH}"

    # A matching file alone cannot prove which historical plan created it.
    # Require its mtime to fall within this plan's execution window, allowing
    # two minutes for clock skew between cluster nodes.
    if [[ "${ACTUAL_SIZE}" == "${EXPECTED_SIZE}"
        && "${ACTUAL_SHA256,,}" == "${EXPECTED_SHA256,,}"
        && "${MODIFIED_EPOCH}" =~ ^[0-9]+$
        && "${PLAN_CREATED_EPOCH}" =~ ^[0-9]+$
        && "${PLAN_UPDATED_EPOCH}" =~ ^[0-9]+$
        && ${MODIFIED_EPOCH} -ge $((PLAN_CREATED_EPOCH - 120))
        && ${MODIFIED_EPOCH} -le $((PLAN_UPDATED_EPOCH + 120)) ]]; then
      LIVE_CONFIRMED=$((LIVE_CONFIRMED + 1))
      echo "live_file_check_status=SIZE_SHA256_AND_MTIME_MATCH"
    else
      echo "live_file_check_status=SIZE_SHA256_OR_MTIME_MISMATCH"
    fi
    echo
  done < <(mysql_raw "
SELECT
  a.assignment_id,
  a.action,
  dst.node_name,
  COALESCE((
    SELECT e.file_path
    FROM dataset_access_audit_event e
    WHERE e.request_id = CONCAT('storage-plan-', p.plan_id,
                                '-assignment-', a.assignment_id)
      AND e.action = 'COPY'
      AND e.decision = 'ALLOWED'
    ORDER BY e.event_id DESC
    LIMIT 1
  ), 'NULL') AS target_file,
  COALESCE(m.authoritative_size_bytes, -1) AS expected_size,
  COALESCE(LOWER(m.digest_value), 'NULL') AS expected_sha256,
  FLOOR(UNIX_TIMESTAMP(p.created_at)) AS plan_created_epoch,
  FLOOR(UNIX_TIMESTAMP(p.updated_at)) AS plan_updated_epoch
FROM scheduling_plan p
JOIN scheduling_assignment a ON a.plan_id = p.plan_id
JOIN node_management dst ON dst.node_id = a.target_node_id
LEFT JOIN dataset_metadata m ON m.dataset_id = a.dataset_id
WHERE p.plan_id = ${PLAN_ID}
  AND a.action IN ('COPY', 'MOVE')
ORDER BY a.assignment_id;")
else
  echo "live_file_check_status=SKIPPED"
fi

read -r TOTAL_ASSIGNMENTS CONFIRMED_ASSIGNMENTS DELETE_ASSIGNMENTS \
  <<<"$(database_match_counts)"

echo
echo "6. 后端记录与文件统计"
echo "plan_status=${STATUS}"
echo "assignment_total=${TOTAL_ASSIGNMENTS}"
echo "database_match_assignments=${CONFIRMED_ASSIGNMENTS}"
echo "delete_assignment_total=${DELETE_ASSIGNMENTS}"
echo "live_file_check_enabled=${LIVE_FILE_CHECK}"
echo "live_file_total=${LIVE_TOTAL}"
echo "live_file_match=${LIVE_CONFIRMED}"
if ((TIMED_OUT == 1)); then
  exit 3
fi
if [[ "${STATUS}" != "COMPLETED" ]]; then
  exit 4
fi
if ((DELETE_ASSIGNMENTS > 0)); then
  exit 4
fi
if [[ "${TOTAL_ASSIGNMENTS}" == "${CONFIRMED_ASSIGNMENTS}"
    && "${TOTAL_ASSIGNMENTS}" != "0"
    && ("${LIVE_FILE_CHECK}" != "1" || "${LIVE_TOTAL}" == "${LIVE_CONFIRMED}") ]]; then
  exit 0
fi

exit 4

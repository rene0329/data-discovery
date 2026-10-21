#!/usr/bin/env bash
# Read-only evidence for denied access and short-lived dataset grants.
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ "${1:-}" == --stage || "${1:-}" == --dry-run ]]; then
  exec "$SCRIPT_DIR/tc42-capture.sh" 4 "$@"
fi
source "$SCRIPT_DIR/show-tc42-evidence.sh"
help_case4() {
  cat <<'HELP'
用例四（在项目目录执行）：
  ./scripts/tc42-case4.sh watch
  ./scripts/tc42-case4.sh denied --user owner-a --dataset_id 3
  ./scripts/tc42-case4.sh granted --user owner-a --dataset_id 3

先在前端以受限用户尝试创建任务，再查 denied；申请短期令牌后查 granted。
任务创建成功后复用：./scripts/tc42-case125.sh --task_id 232
用户名、数据集和任务编号换成本次实际值。
可选：--since 24h（审计/实时日志窗口）、--limit 20、--width 100、--raw。
--since 最多365d；记录不足或令牌已过期时如实展示，不作授权成功推断。
本命令不申请令牌、不修改权限、不创建任务，也不展示令牌内容。
HELP
}
stage="${1:-}"
case "$stage" in watch|denied|granted) shift ;; --help|-h|'') help_case4; exit 0 ;; *) fail "未知阶段：$stage" ;; esac
USER_NAME=""; DATASET=""; SINCE=24h; LIMIT=20; RAW=0
WIDTH="${TC42_TABLE_WIDTH:-$(tput cols 2>/dev/null || echo 120)}"
[[ "$WIDTH" =~ ^[0-9]+$ ]] || WIDTH=120
((WIDTH >= 60)) || WIDTH=60
((WIDTH <= 300)) || WIDTH=300
while (($#)); do
  case "$1" in
    --help|-h) help_case4; exit 0 ;;
    --raw) RAW=1; shift ;;
    --user|--dataset_id|--dataset-id|--since|--limit|--width)
      (($# >= 2)) || fail "$1 缺少参数"
      case "$1" in
        --user) USER_NAME="$2"; [[ "$USER_NAME" =~ ^[A-Za-z0-9._@-]+$ ]] || fail "用户名包含不支持的字符" ;;
        --dataset_id|--dataset-id) DATASET="$2"; require_positive_integer dataset_id "$DATASET" ;;
        --since) SINCE="$2"; require_duration "$SINCE" ;;
        --limit) LIMIT="$2"; [[ "$LIMIT" =~ ^[1-9][0-9]{0,2}$ ]] && ((LIMIT <= 200)) || fail "limit 应为1至200" ;;
        --width) WIDTH="$2"; [[ "$WIDTH" =~ ^[0-9]+$ ]] && ((WIDTH >= 60 && WIDTH <= 300)) || fail "width 应为60至300" ;;
      esac; shift 2 ;;
    *) fail "未知参数：$1" ;;
  esac
done
[[ ${#SINCE} -le 7 ]] || fail "since 最多365d"
WINDOW_SECONDS="${SINCE%?}"
case "${SINCE: -1}" in m) WINDOW_SECONDS=$((WINDOW_SECONDS*60)) ;; h) WINDOW_SECONDS=$((WINDOW_SECONDS*3600)) ;; d) WINDOW_SECONDS=$((WINDOW_SECONDS*86400)) ;; esac
((WINDOW_SECONDS <= 31536000)) || fail "since 最多365d"
if [[ "$stage" == watch ]]; then
  [[ -z "$USER_NAME" && -z "$DATASET" ]] || fail "watch 是权限告警流；用户归属请用 denied/granted 查询"
  echo '【权限告警实时流】Ctrl+C 停止。'
  kubectl logs -n "$NAMESPACE" deployment/practice-server -f --tail=50 --since="$SINCE" |
    grep --line-buffered -E '\[(WARN|ERROR)\].*(DATASET_ACCESS_DENIED|FORBIDDEN|permission denied)'
  exit
fi
[[ -n "$USER_NAME" && -n "$DATASET" ]] || fail "请填写 --user 和 --dataset_id"
backend_init
export TC42_TABLE_WIDTH="$WIDTH" TC42_TABLE_LAYOUT=compact PYTHONIOENCODING=utf-8
[[ "$RAW" == 0 ]] || export TC42_TABLE_LAYOUT=raw
[[ "$(mysql_raw "SELECT COUNT(*) FROM app_user WHERE username='$USER_NAME';")" == 1 ]] || fail "用户不存在"
[[ "$(mysql_raw "SELECT COUNT(*) FROM registered_dataset WHERE dataset_id=$DATASET AND deleted_at IS NULL;")" == 1 ]] || fail "数据集不存在"
echo "【用例四】阶段=$stage 用户=$USER_NAME dataset_id=$DATASET"
echo "审计窗口：最近 $SINCE；最多 $LIMIT 条；数据库时间为 UTC。"
print_metadata
mysql_table "SELECT u.user_id,u.username,u.display_name,u.enabled AS user_enabled,d.name AS domain_name,d.enabled AS domain_enabled,GROUP_CONCAT(r.role_code ORDER BY r.role_code) AS roles FROM app_user u LEFT JOIN collaboration_domain d ON d.domain_id=u.domain_id LEFT JOIN app_user_role ur ON ur.user_id=u.user_id LEFT JOIN app_role r ON r.role_id=ur.role_id WHERE u.username='$USER_NAME' GROUP BY u.user_id,u.username,u.display_name,u.enabled,d.name,d.enabled;"
FILTER=""
if [[ "$stage" == denied ]]; then
  echo '【权限外任务创建被拒绝的审计记录】'
  FILTER="AND decision='DENIED' AND action='TASK_CREATE'"
else
  echo '【该用户对该数据集的访问与申请审计】'
fi
mysql_table "SELECT event_id,request_id,principal,dataset_id,action,target_node,decision,reason,policy_rule,token_expires_at,created_at FROM dataset_access_audit_event WHERE principal='$USER_NAME' AND dataset_id='$DATASET' AND created_at>=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL $WINDOW_SECONDS SECOND) $FILTER ORDER BY event_id DESC LIMIT $LIMIT;"
if [[ "$stage" == granted ]]; then
  echo '【短期令牌申请与有效期】'
  mysql_table "SELECT g.grant_id,u.username,d.dataset_id,d.name,g.reason,g.created_at,g.expires_at,TIMESTAMPDIFF(SECOND,g.created_at,g.expires_at) AS ttl_seconds,CASE WHEN g.created_at>UTC_TIMESTAMP(3) THEN 'NOT_YET_VALID' WHEN g.expires_at<=UTC_TIMESTAMP(3) THEN 'EXPIRED' ELSE 'ACTIVE' END AS grant_status_now,GREATEST(0,TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(3),g.expires_at)) AS remaining_seconds FROM dataset_access_grant g JOIN app_user u ON u.user_id=g.user_id JOIN registered_dataset d ON d.dataset_id=g.dataset_id WHERE u.username='$USER_NAME' AND d.dataset_id=$DATASET ORDER BY g.grant_id DESC LIMIT $LIMIT;"
  echo '【任务执行信息】使用本次任务ID查询：'
  echo './scripts/tc42-case125.sh --task_id <本次任务ID>'
else
  echo '下一步：在前端申请该数据集的短期令牌，再执行 granted 查询申请记录与有效期。'
fi

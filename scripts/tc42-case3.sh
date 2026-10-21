#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Retain the original capture interface for existing callers.
if [[ "${1:-}" == --stage || "${1:-}" == --dry-run ]]; then
  exec "$SCRIPT_DIR/tc42-capture.sh" 3 "$@"
fi
source "$SCRIPT_DIR/show-tc42-evidence.sh"
help_case3() {
  cat <<'HELP'
用例三（在项目目录执行）：
  ./scripts/tc42-case3.sh before
  ./scripts/tc42-case3.sh after-access --task_id 230
  ./scripts/tc42-case3.sh preview
  ./scripts/tc42-case3.sh storage --plan_id 65
  ./scripts/tc42-case3.sh compare --task_id 230 244

默认热数据集7（Tiny ImageNet）、对照数据集9（CIFAR-10）。
before 可用 --dataset_id <热数据集ID> <对照数据集ID> 指定；后续自动沿用。
可选：--width 100、--raw、--log-since 24h、--session <既有采集目录>。
任务创建、热敏方案确认执行均在前端完成，本命令不执行业务修改。
每次 before 建立新采集目录，后续默认沿用最近一次；并行测试请显式 --session。
HELP
}
stage="${1:-}"
case "$stage" in before|after-access|preview|storage|compare) shift ;; --help|-h|'') help_case3; exit 0 ;; *) fail "未知阶段：$stage" ;; esac
ROOT="${TC42_CASE3_STATE_DIR:-/data/topic4-build/evidence/tc42-case3}"
SESSION=""; HOT=7; CONTROL=9; GIVEN_IDS=0; TASKS=(); PLAN=""; RAW=0
WIDTH="${TC42_TABLE_WIDTH:-$(tput cols 2>/dev/null || echo 120)}"
[[ "$WIDTH" =~ ^[0-9]+$ ]] || WIDTH=120
((WIDTH >= 60)) || WIDTH=60
((WIDTH <= 300)) || WIDTH=300
SINCE=24h
while (($#)); do
  case "$1" in
    --help|-h) help_case3; exit 0 ;;
    --raw) RAW=1; shift ;;
    --task_id|--task-id)
      shift
      while (($#)) && [[ "$1" != --* ]]; do require_positive_integer task_id "$1"; TASKS+=("$1"); shift; done ;;
    --dataset_id)
      (($# >= 3)) || fail "--dataset_id 需要热数据集和对照数据集两个编号"
      HOT="$2"; CONTROL="$3"; GIVEN_IDS=1; require_positive_integer dataset_id "$HOT"; require_positive_integer dataset_id "$CONTROL"; shift 3 ;;
    --plan_id|--plan-id|--width|--log-since|--session)
      (($# >= 2)) || fail "$1 缺少参数"
      case "$1" in
        --plan_id|--plan-id) PLAN="$2"; require_positive_integer plan_id "$PLAN" ;;
        --width) WIDTH="$2"; [[ "$WIDTH" =~ ^[0-9]+$ ]] && ((WIDTH >= 60 && WIDTH <= 300)) || fail "width 应为60至300" ;;
        --log-since) SINCE="$2"; require_duration "$SINCE" ;;
        --session) SESSION="$2" ;;
      esac; shift 2 ;;
    *) fail "未知参数：$1" ;;
  esac
done
[[ "$HOT" != "$CONTROL" ]] || fail "热数据集与对照数据集不能相同"
case "$stage" in
  after-access) ((${#TASKS[@]} == 1)) && [[ -z "$PLAN" ]] || fail "after-access 只接受一个 --task_id" ;;
  compare) ((${#TASKS[@]} == 2)) && [[ "${TASKS[0]}" != "${TASKS[1]}" && -z "$PLAN" ]] || fail "compare 需要前后两个不同的 --task_id" ;;
  storage) [[ -n "$PLAN" ]] && ((${#TASKS[@]} == 0)) || fail "storage 只接受 --plan_id" ;;
  *) ((${#TASKS[@]} == 0)) && [[ -z "$PLAN" ]] || fail "本阶段不接受任务或方案编号" ;;
esac
backend_init
export TC42_TABLE_WIDTH="$WIDTH" PYTHONIOENCODING=utf-8
export TC42_TABLE_LAYOUT=compact
[[ "$RAW" == 0 ]] || export TC42_TABLE_LAYOUT=raw
view() { python3 -B "$SCRIPT_DIR/tc42_case3_view.py" "$@" --width "$WIDTH"; }
state_sql() {
  printf '%s' "SELECT d.dataset_id,d.name,d.data_heat,d.heat_updated_at,r.replica_id,n.node_name,n.type AS node_type,r.availability,r.size_bytes FROM registered_dataset d LEFT JOIN dataset_replica r ON r.dataset_id=d.dataset_id LEFT JOIN node_management n ON n.node_id=r.node_id WHERE d.dataset_id IN ($HOT,$CONTROL) AND d.deleted_at IS NULL ORDER BY d.dataset_id,r.replica_id;"
}
current_state() {
  echo '【当前两数据集的热度与存储位置】'
  if [[ "$RAW" == 0 ]]; then mysql_xml "$(state_sql)" | view state
  else mysql_table "$(state_sql)"; fi
}
if [[ "$stage" == before ]]; then
  [[ -z "$SESSION" ]] || fail "before 会创建新目录，不接受 --session"
  [[ "$(mysql_raw "SELECT COUNT(*) FROM registered_dataset WHERE dataset_id IN ($HOT,$CONTROL) AND deleted_at IS NULL;")" == 2 ]] || fail "两个数据集必须存在"
  mkdir -p "$ROOT"
  SESSION="$(mktemp -d "$ROOT/run-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
  mysql_xml "$(state_sql)" > "$SESSION/before.xml"
  mysql_raw 'SELECT UNIX_TIMESTAMP(UTC_TIMESTAMP(3));' > "$SESSION/before.epoch"
  printf '%s %s\n' "$HOT" "$CONTROL" > "$SESSION/dataset-ids"
  latest_temp="$(mktemp "$ROOT/.latest-XXXXXX")"
  printf '%s\n' "$SESSION" > "$latest_temp"
  mv "$latest_temp" "$ROOT/latest"
else
  if [[ -z "$SESSION" ]]; then
    [[ -f "$ROOT/latest" ]] || fail "请先执行 before，或通过 --session 选择已有采集目录"
    SESSION="$(cat "$ROOT/latest")"
  fi
  [[ -f "$SESSION/before.xml" && -f "$SESSION/dataset-ids" ]] || fail "采集目录缺少测试前记录"
  read -r saved_hot saved_control < "$SESSION/dataset-ids"
  [[ "$GIVEN_IDS" == 0 || "$HOT $CONTROL" == "$saved_hot $saved_control" ]] || fail "数据集与采集目录不一致"
  HOT="$saved_hot"; CONTROL="$saved_control"
  require_positive_integer hot "$HOT"; require_positive_integer control "$CONTROL"
fi
printf '【用例三】阶段=%s  热数据集=%s  对照数据集=%s\n采集目录：%s\n' "$stage" "$HOT" "$CONTROL" "$SESSION"
task_sql() {
  local ids; ids="$(join_integer_ids "${TASKS[@]}")"
  printf '%s' "SELECT task_id,dataset_ids_json,runtime_image_id,resource_overrides_json,execution_mode,status,execution_evidence_complete,UNIX_TIMESTAMP(started_at) AS started_epoch,UNIX_TIMESTAMP(finished_at) AS finished_epoch FROM task_management WHERE task_id IN ($ids) ORDER BY task_id;"
}
logs() {
  local kind="$1" id="$2" external="${3:-}"
  { kubectl logs -n "$NAMESPACE" -l app=practice-server --all-containers=true --tail=-1 --since="$SINCE" 2>/dev/null || true; } |
    awk -v kind="$kind" -v id="$id" -v external="$external" '{
      gsub(/\033\[[0-9;]*[mK]/, "")
      if (kind == "task" && $0 ~ ("taskId=" id "([^0-9]|$)") && $0 ~ /热度|计入温度/) print
      if (kind == "plan" && ($0 ~ ("planId=" id "([^0-9]|$)") || (external != "" && index($0,"externalPlanId=" external " ") > 0)) && $0 ~ /SCHEDULING_|STORAGE_PLACEMENT_|调度/) print
    }' | view logs
}
case "$stage" in
  before)
    echo '【执行前对照】以下是刚采集的真实记录；后续差值以这次采集为基准。'
    view state < "$SESSION/before.xml"
    echo '下一步：在前端仅运行热数据集，取得任务ID后执行 after-access。' ;;
  after-access)
    mysql_xml "$(task_sql)" | view validate-task --ids "${TASKS[@]}" --hot "$HOT" --control "$CONTROL"
    epoch="$(mysql_raw "SELECT UNIX_TIMESTAMP(create_time) FROM task_management WHERE task_id=${TASKS[0]};")"
    python3 -B -c 'import sys; sys.exit(0 if float(sys.argv[1]) <= float(sys.argv[2]) else 1)' "$(cat "$SESSION/before.epoch")" "$epoch" || fail "before 在该任务创建之后，不能作为执行前对照"
    mysql_table "$(task_sql)"
    file="$SESSION/after-access-${TASKS[0]}-$(date -u +%Y%m%dT%H%M%SZ).xml"
    mysql_xml "$(state_sql)" > "$file"
    echo '【热度前后对照】当前值与 before 的真实差值；任务未完成时请稍后重查。'
    view delta --before "$SESSION/before.xml" < "$file"
    echo '【本任务热度更新日志】'; logs task "${TASKS[0]}"
    current_state ;;
  preview)
    echo '【当前热敏存储节点选择】只做预览，不提交、不迁移；这是当前全系统方案，不是历史方案。'
    echo '规则：达到热度阈值后，沿通往就近计算节点的路径选择更靠近计算节点、容量可用的存储节点；再检查时延改善门槛。'
    export PREVIEW_OUTPUT="$SESSION/preview-$(date -u +%Y%m%dT%H%M%SZ).json"
    view preview
    echo '下一步：在前端预览并确认热敏存储，取得 plan_id 后执行 storage。' ;;
  storage)
    [[ "$(mysql_raw "SELECT COUNT(*) FROM scheduling_plan p JOIN scheduling_assignment a ON a.plan_id=p.plan_id WHERE p.plan_id=$PLAN AND p.algorithm_name='热敏存储' AND a.dataset_id=$HOT;")" != 0 ]] || fail "方案不存在、不是热敏存储或不包含本次热数据集"
    echo '【热敏存储方案及执行结果】历史记录；当前文件或副本状态不作为历史时刻的还原。'
    mysql_table "SELECT p.plan_id,p.external_plan_id,p.status AS plan_status,p.error_message AS plan_error,p.algorithm_name,p.created_at,p.updated_at,a.assignment_id,a.dataset_id,src.node_name AS source_node,dst.node_name AS target_node,a.action,a.status AS assignment_status,a.error_message FROM scheduling_plan p JOIN scheduling_assignment a ON a.plan_id=p.plan_id LEFT JOIN node_management src ON src.node_id=a.source_node_id LEFT JOIN node_management dst ON dst.node_id=a.target_node_id WHERE p.plan_id=$PLAN ORDER BY a.assignment_id;"
    echo '【方案节点选择与执行日志】实际留存日志；无记录时不以当前预览替代。'
    external="$(mysql_raw "SELECT external_plan_id FROM scheduling_plan WHERE plan_id=$PLAN;")"
    logs plan "$PLAN" "$external"
    echo '【迁移访问审计】按本方案请求编号关联。'
    mysql_table "SELECT event_id,request_id,dataset_id,action,target_node,decision,reason,file_path,created_at FROM dataset_access_audit_event WHERE request_id LIKE 'storage-plan-$PLAN-assignment-%' ORDER BY event_id;"
    printf '%s\n' "$PLAN" > "$SESSION/plan-id"
    current_state
    echo '下一步：在前端再次运行同一热数据集，用 compare 提供前后两次任务ID。' ;;
  compare)
    echo '【前后任务条件核对】同数据集、镜像、资源和模式；任务必须完成且执行证据完整。'
    mysql_xml "$(task_sql)" | view compare-check --ids "${TASKS[@]}" --hot "$HOT" --control "$CONTROL"
    mysql_table "$(task_sql)"
    if [[ -f "$SESSION/plan-id" ]]; then
      PLAN="$(cat "$SESSION/plan-id")"; require_positive_integer plan_id "$PLAN"
      echo '【任务与热敏存储的时间关系】应为前任务结束 → 方案创建及完成 → 后任务开始。'
      mysql_table "SELECT p.plan_id,p.status AS plan_status,b.finished_at AS before_task_finished,p.created_at AS plan_created,p.updated_at AS plan_updated,a.started_at AS after_task_started,CASE WHEN p.status='COMPLETED' AND b.finished_at<=p.created_at AND a.started_at>=p.updated_at THEN 'ORDER_CONFIRMED' ELSE 'NOT_CONFIRMED' END AS chronology FROM scheduling_plan p JOIN task_management b ON b.task_id=${TASKS[0]} JOIN task_management a ON a.task_id=${TASKS[1]} WHERE p.plan_id=$PLAN;"
    else
      echo '没有关联方案记录，不能将两次任务差异直接归因于热敏存储。'
    fi
    ids="$(join_integer_ids "${TASKS[@]}")"
    echo '【调度时记录的访问路径时延】latency_ms 是调度观测的网络路径时延，不是实际读取耗时；NULL 不按0处理。'
    mysql_table "SELECT task_id,dataset_id,execution_mode,JSON_UNQUOTE(JSON_EXTRACT(details_json,'$.sourceNode')) AS source_node,JSON_UNQUOTE(JSON_EXTRACT(details_json,'$.targetNode')) AS target_node,JSON_EXTRACT(details_json,'$.latencyMs') AS latency_ms,JSON_EXTRACT(details_json,'$.bandwidthMbps') AS bandwidth_mbps,JSON_EXTRACT(details_json,'$.pathNodeIds') AS path_node_ids,occurred_at FROM task_execution_event WHERE task_id IN ($ids) AND dataset_id=$HOT AND event_type='PLACEMENT_SELECTED' ORDER BY task_id,event_id;"
    echo '【访问路径时延变化】只对同一数据集、同一执行模式配对。'
    mysql_xml "SELECT task_id,dataset_id,execution_mode,JSON_UNQUOTE(JSON_EXTRACT(details_json,'$.latencyMs')) AS latency_ms FROM task_execution_event WHERE task_id IN ($ids) AND dataset_id=$HOT AND event_type='PLACEMENT_SELECTED' ORDER BY task_id,event_id;" | view latency --ids "${TASKS[@]}"
    echo '【实际数据准备耗时】duration_ms 来自完成事件，不等同于 latency_ms。'
    mysql_table "SELECT task_id,dataset_id,execution_mode,node_name,bytes_processed,duration_ms,attempt,occurred_at FROM task_execution_event WHERE task_id IN ($ids) AND dataset_id=$HOT AND event_type='DATA_PREPARATION_READY' ORDER BY task_id,event_id;"
    echo '【实际读取记录】如无记录，说明该路径没有单独记录读取事件，不能用网络时延填充。'
    mysql_table "SELECT task_id,dataset_id,consumer_node_id,source_node_id,bytes_read,cache_hit_bytes,duration_ms,success,completed_at FROM dataset_access_event WHERE task_id IN ($ids) AND dataset_id=$HOT ORDER BY event_id;"
    current_state ;;
esac

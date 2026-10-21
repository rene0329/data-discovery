#!/usr/bin/env bash
# Collect the document's screenshot evidence by case; never run business mutations.
set -Eeuo pipefail
umask 077
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EVIDENCE="${TC42_EVIDENCE_SCRIPT:-${SCRIPT_DIR}/show-tc42-evidence.sh}"
API="${TC42_API_SCRIPT:-${SCRIPT_DIR}/tc42-api.sh}"
CENTRAL_TASK_ID="${CENTRAL_TASK_ID:-228}"
DISTRIBUTED_TASK_ID="${DISTRIBUTED_TASK_ID:-231}"
HEAT_TASK_ID="${HEAT_TASK_ID:-230}"
BEFORE_TASK_ID="${BEFORE_TASK_ID:-230}"
AFTER_TASK_ID="${AFTER_TASK_ID:-244}"
PLAN_ID="${PLAN_ID:-65}"
AUTH_TASK_ID="${AUTH_TASK_ID:-232}"
MULTI_TASK_ID="${MULTI_TASK_ID:-233}"
CATDOG_TASK_ID="${CATDOG_TASK_ID:-234}"
NLP_TASK_ID="${NLP_TASK_ID:-235}"
REPEAT_TASK_IDS_TEXT="${REPEAT_TASK_IDS_TEXT:-236 237 238 239 240}"
PRINCIPAL="${PRINCIPAL:-owner-a}"
LOG_SINCE="${LOG_SINCE:-1h}"
WAIT_SECONDS="${WAIT_SECONDS:-1800}"
OUTPUT_ROOT="${OUTPUT_ROOT:-/data/topic4-build/evidence/tc42-captures}"
STAGE=records
TASK_ID=""
REPEAT_IDS_GIVEN=0
DRY_RUN=0
CASE="${1:-}"

usage() {
  cat <<'HELP'
按 TC-4.2 测试用例执行文档截图命令，并逐项保存原始输出。
用法：tc42-capture.sh <1|2|3|4|5|all> [选项]
      tc42-case1.sh [选项]  （其他用例依次为 case2 至 case5）

默认 records：一键查询已完成测试的记录，并保存当前状态；不会制造“前后”观测。
实际任务：集中式228，分布式231，热度访问230，迁移前230/后244，
          授权后232，多数据集233，单数据集234/235，重复236–240；热敏计划65。
每次先核对任务是否存在、计算模式及数据集是否匹配，再执行截图命令。

选项：
  --stage <阶段>       records（默认）或以下现场采集阶段：
                       用例1/2/5：before
                       用例5：multi、single、repeat（只查看 --task-id 指定的一个任务）
                              repeat-summary（五轮结束后再汇总）
                       用例3：before、after-access、after-storage、decay
                       用例4：before-grant、after-grant
  --output-dir <目录>  保存位置；每次建立独立目录，不覆盖既有输出
  --log-since <时长>   容器日志范围，默认1h；历史日志可能已清理
  --wait-seconds <秒>  after-storage 等待迁移及文件登记，默认1800
  --central-task <ID> --distributed-task <ID>
  --heat-task <ID> --before-task <ID> --after-task <ID> --plan <ID>
  --auth-task <ID> --principal <用户名>
  --task-id <ID>       用例5当前这一步在前端返回的任务ID；必须明确填写
  --multi-task <ID> --catdog-task <ID> --nlp-task <ID>
  --repeat-tasks "ID ID ID ID ID"   本轮至少五个不同任务
  --dry-run            仅展示将执行的命令；不联网、不写文件
  --help               显示用法

示例：
  ./scripts/tc42-capture.sh all
  ./scripts/tc42-case3.sh
  ./scripts/tc42-case3.sh --stage before
  ./scripts/tc42-case3.sh --stage after-access --heat-task 230
  ./scripts/tc42-case3.sh --stage after-storage --plan 65
  ./scripts/tc42-case3.sh --stage decay
  ./scripts/tc42-case5.sh multi 233
  ./scripts/tc42-case5.sh single 234
  ./scripts/tc42-case5.sh repeat 236
  ./scripts/tc42-case5.sh repeat-summary 236 237 238 239 240

records 不校验当前文件是否仍属于历史计划；after-storage 按原命令执行目标文件校验。
返回0：查询均成功；1：有查询或文件核查失败；2：参数或任务对应关系不正确。
查询成功不等于测试通过。计算、更新温度、迁移及授权仍在平台操作。
HELP
}
fail() { echo "错误：$*" >&2; exit 2; }
case "$CASE" in
  --help) usage; exit 0 ;;
  1|2|3|4|5|all) shift ;;
  *) usage >&2; exit 2 ;;
esac
while (($#)); do
  case "$1" in
    --help) usage; exit 0 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --stage|--task-id|--output-dir|--log-since|--wait-seconds|--central-task|--distributed-task|--heat-task|--before-task|--after-task|--plan|--auth-task|--multi-task|--catdog-task|--nlp-task|--repeat-tasks|--principal)
      (($# >= 2)) || fail "$1 缺少参数"
      case "$1" in
        --stage) STAGE="$2" ;; --output-dir) OUTPUT_ROOT="$2" ;;
        --task-id) TASK_ID="$2" ;;
        --log-since) LOG_SINCE="$2" ;; --wait-seconds) WAIT_SECONDS="$2" ;;
        --central-task) CENTRAL_TASK_ID="$2" ;; --distributed-task) DISTRIBUTED_TASK_ID="$2" ;;
        --heat-task) HEAT_TASK_ID="$2" ;; --before-task) BEFORE_TASK_ID="$2" ;;
        --after-task) AFTER_TASK_ID="$2" ;; --plan) PLAN_ID="$2" ;;
        --auth-task) AUTH_TASK_ID="$2" ;; --multi-task) MULTI_TASK_ID="$2" ;;
        --catdog-task) CATDOG_TASK_ID="$2" ;; --nlp-task) NLP_TASK_ID="$2" ;;
        --repeat-tasks) REPEAT_TASK_IDS_TEXT="$2"; REPEAT_IDS_GIVEN=1 ;; --principal) PRINCIPAL="$2" ;;
      esac
      shift 2 ;;
    *) fail "未知选项：$1；查看用法请用 --help" ;;
  esac
done
case "$CASE:$STAGE" in
  5:multi|5:single|5:repeat)
    [[ "$TASK_ID" =~ ^[1-9][0-9]*$ ]] || fail "请填写本次前端返回的实际任务ID；运行 tc42-case5.sh --help 查看分步用法" ;;
  5:records)
    fail "用例5请分步选择 multi、single、repeat 或 repeat-summary，并填写实际任务ID；查看用法请用 --help" ;;
  5:repeat-summary)
    [[ "$REPEAT_IDS_GIVEN" == 1 ]] || fail "五轮汇总请通过 --repeat-tasks 明确填写本轮实际任务ID"
    [[ -z "$TASK_ID" ]] || fail "五轮汇总请使用 --repeat-tasks" ;;
  *) [[ -z "$TASK_ID" ]] || fail "--task-id 仅用于用例5的 multi、single、repeat 阶段" ;;
esac
for value in "$CENTRAL_TASK_ID" "$DISTRIBUTED_TASK_ID" "$HEAT_TASK_ID" "$BEFORE_TASK_ID" "$AFTER_TASK_ID" "$PLAN_ID" "$AUTH_TASK_ID" "$MULTI_TASK_ID" "$CATDOG_TASK_ID" "$NLP_TASK_ID"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || fail "任务和计划编号须为正整数：$value"
done
[[ "$PRINCIPAL" =~ ^[A-Za-z0-9._@-]+$ ]] || fail "用户名包含不支持的字符"
[[ "$LOG_SINCE" =~ ^[1-9][0-9]*[smhd]$ ]] || fail "日志范围应为1h、30m等"
[[ "$WAIT_SECONDS" =~ ^[0-9]+$ ]] || fail "等待秒数必须是非负整数"
REPEAT_TASK_IDS=()
read -r -a REPEAT_TASK_IDS <<<"$REPEAT_TASK_IDS_TEXT" || true
if [[ "$STAGE" == repeat-summary || "$STAGE" == records ]]; then
((${#REPEAT_TASK_IDS[@]} >= 5)) || fail "重复测试至少需要五个任务编号"
SEEN_IDS=' '
for value in "${REPEAT_TASK_IDS[@]}"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || fail "重复任务编号须为正整数：$value"
  [[ "$SEEN_IDS" != *" $value "* ]] || fail "重复任务编号不能重复：$value"
  SEEN_IDS+="$value "
done
fi
case "$CASE:$STAGE" in
  *:records|1:before|2:before|5:before|5:multi|5:single|5:repeat|5:repeat-summary|3:before|3:after-access|3:after-storage|3:decay|4:before-grant|4:after-grant) ;;
  *) fail "用例 $CASE 不支持阶段 $STAGE" ;;
esac
if [[ "$DRY_RUN" == 0 ]]; then
  for tool in jq tee mktemp date; do command -v "$tool" >/dev/null || fail "缺少命令：$tool"; done
  [[ -x "$EVIDENCE" && -x "$API" ]] || fail "找不到证据查询脚本或API脚本"
  mkdir -p "$OUTPUT_ROOT"
  RUN_DIR="$(mktemp -d "${OUTPUT_ROOT%/}/$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
  printf '用例\t阶段\t步骤\t退出码\t输出文件\n' >"$RUN_DIR/index.tsv"
  printf '查询时间（UTC）：%s\n执行主机：%s\n' "$(date -u +%FT%TZ)" "$(hostname)" >"$RUN_DIR/run.txt"
  if [[ "$CASE" == 5 && "$STAGE" != records && "$STAGE" != before ]]; then
    printf 'STAGE=%s\nTASK_ID=%s\nREPEAT_TASK_IDS=%s\nLOG_SINCE=%s\n' "$STAGE" "$TASK_ID" \
      "$([[ "$STAGE" == repeat-summary ]] && echo "$REPEAT_TASK_IDS_TEXT" || true)" "$LOG_SINCE" >>"$RUN_DIR/run.txt"
  else
  printf 'CENTRAL_TASK_ID=%s\nDISTRIBUTED_TASK_ID=%s\nHEAT_TASK_ID=%s\nBEFORE_TASK_ID=%s\nAFTER_TASK_ID=%s\nPLAN_ID=%s\nAUTH_TASK_ID=%s\nMULTI_TASK_ID=%s\nCATDOG_TASK_ID=%s\nNLP_TASK_ID=%s\nREPEAT_TASK_IDS=%s\nPRINCIPAL=%s\nSTAGE=%s\nLOG_SINCE=%s\n' \
    "$CENTRAL_TASK_ID" "$DISTRIBUTED_TASK_ID" "$HEAT_TASK_ID" "$BEFORE_TASK_ID" "$AFTER_TASK_ID" "$PLAN_ID" "$AUTH_TASK_ID" "$MULTI_TASK_ID" "$CATDOG_TASK_ID" "$NLP_TASK_ID" "$REPEAT_TASK_IDS_TEXT" "$PRINCIPAL" "$STAGE" "$LOG_SINCE" >>"$RUN_DIR/run.txt"
  fi
  echo "本次输出目录：$RUN_DIR"
else
  echo "预览：不联网、不写文件。"
fi
FAILURES=0
IDENTITY_FAILURES=0
STEP_NUMBER=0
print_command() { printf '命令：'; printf ' %q' "$@"; printf '\n'; }
run_step() {
  local file="$1" title="$2" code=0
  shift 2
  STEP_NUMBER=$((STEP_NUMBER + 1))
  printf '\n========== TC-4.2.%s / %s / %02d %s ==========\n' "$CURRENT_CASE" "$STAGE" "$STEP_NUMBER" "$title"
  if [[ "$1" == selected_batch ]]; then
    local position
    for ((position=2; position<=$#; position++)); do
      print_command "$EVIDENCE" decision "${!position}" --selected
    done
  else
    print_command "$@"
  fi
  [[ "$DRY_RUN" == 0 ]] || return 0
  local log="$CASE_DIR/$(printf '%02d' "$STEP_NUMBER")-$file.txt"
  set +e
  "$@" 2>&1 | tee "$log"
  local statuses=("${PIPESTATUS[@]}")
  set -e
  if [[ "${statuses[0]}" != 0 ]]; then code="${statuses[0]}"; fi
  if [[ "${statuses[1]}" != 0 ]]; then code=1; fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$CURRENT_CASE" "$STAGE" "$title" "$code" "$log" >>"$RUN_DIR/index.tsv"
  if ((code)); then
    FAILURES=$((FAILURES + 1))
    echo "该步骤未成功（退出码 $code），输出已保留，继续采集后续记录。" >&2
  fi
}
verify_task() {
  local id="$1" mode="$2" datasets="$3" target="$CASE_DIR/validation/task-$1.json" rule="${4:-exact}"
  if [[ "$DRY_RUN" == 1 ]]; then echo "核对任务：ID=$id 模式=$mode 数据集=$datasets"; return 0; fi
  if ! "$API" --raw task "$id" >"$target" 2>"$target.stderr"; then
    echo "无法读取任务 $id，见 $target.stderr" >&2; return 1
  fi
  if ! jq -e --argjson id "$id" --arg mode "$mode" --argjson ids "$datasets" --arg rule "$rule" \
    '.code == 0 and (.data.task | .taskId == $id and .executionMode == $mode and
      ((.datasetIdsJson | fromjson | sort) as $selected |
        if $rule == "one-of" then ($selected | length) == 1 and (($ids | index($selected[0])) != null)
        else $selected == ($ids | sort) end))' "$target" >/dev/null; then
    echo "任务 $id 的实际模式或数据集不匹配，本用例停止，防止展示错任务。" >&2; return 1
  fi
  jq -r '.data.task | "已核对实际任务：\(.taskId)  \(.executionMode)  \(.selectedData)  \(.status)"' "$target"
}
verify_plan() {
  [[ "$DRY_RUN" == 0 ]] || { echo "核对热敏计划：$PLAN_ID，必须包含数据集7"; return 0; }
  local target="$CASE_DIR/validation/plan-$PLAN_ID.json"
  "$API" --raw plan "$PLAN_ID" >"$target" 2>"$target.stderr" || return 1
  jq -e --argjson id "$PLAN_ID" '.code==0 and .data.plan.planId==$id and .data.plan.algorithmName=="热敏存储" and any(.data.assignments[]; .datasetId==7)' "$target" >/dev/null
}
preflight() {
  case "$CURRENT_CASE:$STAGE" in
    1:records) verify_task "$CENTRAL_TASK_ID" CENTRALIZED '[1,4,6]' ;;
    2:records)
      verify_task "$CENTRAL_TASK_ID" CENTRALIZED '[1,4,6]' && verify_task "$DISTRIBUTED_TASK_ID" IN_PLACE '[1,4,6]' ;;
    3:records)
      verify_task "$HEAT_TASK_ID" COMPARISON '[7]' || return 1
      if [[ "$BEFORE_TASK_ID" != "$HEAT_TASK_ID" ]]; then verify_task "$BEFORE_TASK_ID" COMPARISON '[7]' || return 1; fi
      verify_task "$AFTER_TASK_ID" COMPARISON '[7]' && verify_plan ;;
    3:after-access) verify_task "$HEAT_TASK_ID" COMPARISON '[7]' ;;
    3:after-storage) verify_plan ;;
    4:records) verify_task "$AUTH_TASK_ID" COMPARISON '[3]' ;;
    5:multi) verify_task "$TASK_ID" COMPARISON '[1,4,6]' ;;
    5:single) verify_task "$TASK_ID" COMPARISON '[3,5]' one-of ;;
    5:repeat) verify_task "$TASK_ID" COMPARISON '[5]' ;;
    5:repeat-summary)
      for id in "${REPEAT_TASK_IDS[@]}"; do verify_task "$id" COMPARISON '[5]' || return 1; done ;;
    5:records)
      verify_task "$MULTI_TASK_ID" COMPARISON '[1,4,6]' || return 1
      verify_task "$CATDOG_TASK_ID" COMPARISON '[5]' || return 1
      verify_task "$NLP_TASK_ID" COMPARISON '[3]' || return 1
      for id in "${REPEAT_TASK_IDS[@]}"; do verify_task "$id" COMPARISON '[5]' || return 1; done ;;
  esac
}
selected_batch() {
  local result=0 id
  for id in "$@"; do
    echo "--- 实际任务 $id 的最终节点选择 ---"
    "$EVIDENCE" decision "$id" --selected || result=1
  done
  return "$result"
}
task_step() { run_step "task-$1" "$2（任务$1）" "$EVIDENCE" task "$1" --log-since "$LOG_SINCE"; }
case_steps() {
  case "$CURRENT_CASE" in
    1|2)
      if [[ "$STAGE" == before ]]; then
        run_step baseline-before '操作前初始存储、节点和网络' "$EVIDENCE" baseline 1 4 6
        return
      fi
      run_step baseline-current '当前存储、节点和网络（非历史T0）' "$EVIDENCE" baseline 1 4 6
      local id="$CENTRAL_TASK_ID"
      [[ "$CURRENT_CASE" == 1 ]] || id="$DISTRIBUTED_TASK_ID"
      run_step "decision-$id" "完整节点选择记录（任务$id）" "$EVIDENCE" decision "$id"
      task_step "$id" '任务、传输、校验及计算记录'
      run_step "selected-$id" "最终节点选择（任务$id）" "$EVIDENCE" decision "$id" --selected
      if [[ "$CURRENT_CASE" == 2 ]]; then
        run_step compare '集中式与分布式最终节点对比' selected_batch "$CENTRAL_TASK_ID" "$DISTRIBUTED_TASK_ID"
      fi ;;
    3)
      case "$STAGE" in
        before)
          run_step heat-before '操作前温度' "$EVIDENCE" heat 7 9
          run_step storage-before '操作前存储位置' "$EVIDENCE" baseline 7 9 ;;
        after-access)
          task_step "$HEAT_TASK_ID" '访问任务读取记录'
          run_step heat-after-access '页面更新热度后的当前温度' "$EVIDENCE" heat 7 9 ;;
        after-storage)
          run_step "plan-$PLAN_ID" '等待迁移并核对目标文件大小和SHA-256' "$EVIDENCE" plan "$PLAN_ID" --wait-seconds "$WAIT_SECONDS" --log-since "$LOG_SINCE"
          run_step storage-after '迁移操作后的当前副本与存储位置' "$EVIDENCE" baseline 7 9 ;;
        decay)
          run_step heat-decay '停止访问后的本次温度观测（不自动更新热度）' "$EVIDENCE" heat 7 9 ;;
        records)
          run_step heat-current '当前温度及读取记录（非访问前快照）' "$EVIDENCE" heat 7 9
          run_step storage-current '当前存储位置（非历史T0）' "$EVIDENCE" baseline 7 9
          task_step "$HEAT_TASK_ID" '温度访问任务'
          run_step "plan-$PLAN_ID" '历史迁移计划和审计（不验证当前文件）' "$EVIDENCE" plan "$PLAN_ID" --records-only --log-since "$LOG_SINCE"
          task_step "$AFTER_TASK_ID" '迁移后计算任务'
          run_step latency '迁移前后实际计算节点及网络时延' selected_batch "$BEFORE_TASK_ID" "$AFTER_TASK_ID"
          run_step times '迁移前后两种模式的数据移动时间' "$EVIDENCE" speedup "$BEFORE_TASK_ID" "$AFTER_TASK_ID" ;;
      esac ;;
    4)
      case "$STAGE" in
        before-grant) run_step access-before-grant '本次授权前的访问拒绝与既有授权记录' "$EVIDENCE" access "$PRINCIPAL" 3 ;;
        after-grant) run_step access-after-grant '页面申请授权后的审计及授权期限' "$EVIDENCE" access "$PRINCIPAL" 3 ;;
        records)
          run_step "access-task-$AUTH_TASK_ID" '访问拒绝、授权及授权后任务' "$EVIDENCE" access "$PRINCIPAL" 3 "$AUTH_TASK_ID"
          task_step "$AUTH_TASK_ID" '授权后两种模式执行记录' ;;
      esac ;;
    5)
      case "$STAGE" in
        multi|single|repeat)
          local label
          case "$STAGE" in multi) label=多数据集 ;; single) label=单数据集 ;; repeat) label=本轮重复测试 ;; esac
          task_step "$TASK_ID" "$label：当前任务执行日志"
          run_step "selected-$TASK_ID" "$label：当前任务最终调度节点" "$EVIDENCE" decision "$TASK_ID" --selected
          run_step "speedup-$TASK_ID" "$label：当前任务计时和加速比" "$EVIDENCE" speedup "$TASK_ID"
          return ;;
        repeat-summary)
          run_step repeat-decisions '已指定各轮任务的最终节点选择' selected_batch "${REPEAT_TASK_IDS[@]}"
          run_step repeat-speedup '已指定各轮任务的计时和加速比汇总' "$EVIDENCE" speedup "${REPEAT_TASK_IDS[@]}"
          return ;;
      esac
      if [[ "$STAGE" == before ]]; then
        run_step baseline-before '操作前五个数据集状态' "$EVIDENCE" baseline 1 4 6 5 3
        return
      fi
      run_step baseline-current '五个数据集当前状态（非历史T0）' "$EVIDENCE" baseline 1 4 6 5 3
      task_step "$MULTI_TASK_ID" '多数据集任务执行记录'
      run_step multi-speedup '多数据集累计计时和逐数据集明细' "$EVIDENCE" speedup "$MULTI_TASK_ID"
      task_step "$CATDOG_TASK_ID" 'catdog单数据集执行记录'
      task_step "$NLP_TASK_ID" 'nlpcc2013单数据集执行记录'
      run_step single-speedup '两个单数据集计时及加速比' "$EVIDENCE" speedup "$CATDOG_TASK_ID" "$NLP_TASK_ID"
      run_step repeat-decisions '至少五轮最终节点选择' selected_batch "${REPEAT_TASK_IDS[@]}"
      run_step repeat-speedup '至少五轮计时及执行状态' "$EVIDENCE" speedup "${REPEAT_TASK_IDS[@]}"
      run_step speedup-summary '多数据集、单数据集和五轮重复汇总' "$EVIDENCE" speedup "$MULTI_TASK_ID" "$CATDOG_TASK_ID" "$NLP_TASK_ID" "${REPEAT_TASK_IDS[@]}" ;;
  esac
}
run_case() {
  CURRENT_CASE="$1"
  STEP_NUMBER=0
  if [[ "$DRY_RUN" == 0 ]]; then
    CASE_DIR="$RUN_DIR/TC-4.2.$CURRENT_CASE"
    mkdir -p "$CASE_DIR/validation"
  else
    CASE_DIR=/preview
  fi
  if ! preflight; then
    FAILURES=$((FAILURES + 1))
    IDENTITY_FAILURES=$((IDENTITY_FAILURES + 1))
    [[ "$DRY_RUN" == 1 ]] || printf '%s\t%s\t任务对应关系核对\t2\t%s\n' "$CURRENT_CASE" "$STAGE" "$CASE_DIR/validation" >>"$RUN_DIR/index.tsv"
    echo "TC-4.2.$CURRENT_CASE 因任务或计划不匹配而跳过。" >&2
    return
  fi
  case_steps
}
if [[ "$CASE" == all ]]; then
  for current in 1 2 3 4 5; do run_case "$current"; done
else
  run_case "$CASE"
fi
printf '\n采集结束：失败步骤 %s。查询完成情况不代表用例通过。\n' "$FAILURES"
[[ "$DRY_RUN" == 1 ]] || echo "文件索引：$RUN_DIR/index.tsv"
((IDENTITY_FAILURES == 0)) || exit 2
((FAILURES == 0))

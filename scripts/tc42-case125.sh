#!/usr/bin/env bash
# Read-only, task-ID-only entry point for cases 1, 2 and 5.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
usage() {
  cat <<'HELP'
用法：
  ./scripts/tc42-case125.sh --task_id 228
  ./scripts/tc42-case125.sh --task_id 228 231 274
  ./scripts/tc42-case125.sh --task_id 228,231,274

默认按终端宽度排版；--width 100 指定宽度，--raw 查看完整原始表。
按给定顺序分别展示任务证据，自动识别集中式、分布式或对比任务。
多个ID也可用中文逗号或重复 --task_id；不会自动将不同任务配对计算加速比。
测试前副本记录使用已保存的 snapshot；缺失时明确提示，不以当前状态代替。
HELP
}
fail() { echo "错误：$*" >&2; exit 2; }
raw=0
width="${TC42_TABLE_WIDTH:-$(tput cols 2>/dev/null || echo 120)}"
[[ "$width" =~ ^[0-9]+$ ]] || width=120
((width >= 60)) || width=60
((width <= 300)) || width=300
ids=()
while (($#)); do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --raw) raw=1; shift ;;
    --width)
      (($# >= 2)) || fail "--width 缺少列宽"
      [[ "$2" =~ ^[0-9]+$ ]] && (( $2 >= 60 && $2 <= 300 )) || fail "列宽应为60至300"
      width="$2"; shift 2 ;;
    --task_id|--task-id)
      shift
      (($#)) && [[ "$1" != --* ]] || fail "--task_id 后请填写任务ID"
      while (($#)) && [[ "$1" != --* ]]; do
        value="${1//，/,}"
        [[ "$value" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]] || fail "任务ID须为正整数，多个ID用空格或逗号分隔：$1"
        IFS=',' read -r -a parts <<< "$value"
        ids+=("${parts[@]}")
        shift
      done ;;
    *) fail "未知参数：$1；请使用 --task_id <任务ID>" ;;
  esac
done
((${#ids[@]})) || { usage >&2; exit 2; }
if [[ "$raw" == 1 ]]; then
  export TC42_TABLE_LAYOUT=raw
else
  export TC42_TABLE_LAYOUT=compact
fi
export TC42_TABLE_WIDTH="$width"
status=0
for id in "${ids[@]}"; do
  printf '\n==================== 任务 %s ====================\n' "$id"
  if "$SCRIPT_DIR/show-tc42-evidence.sh" report "$id"; then
    :
  else
    code=$?
    echo "任务 $id 查询失败（退出码 $code），继续查询其余任务。" >&2
    status=1
  fi
done
exit "$status"

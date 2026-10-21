#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
usage() {
  cat <<'HELP'
TC-4.2.5 分步查看前端任务的日志和计时，不自动展示其他类型或历史任务。

在前端创建对应任务，拿到ID后执行：
  tc42-case5.sh multi 233       多数据集：ciao、epinions、yelp
  tc42-case5.sh single 234      单数据集：catdog
  tc42-case5.sh single 235      单数据集：nlpcc2013
  tc42-case5.sh repeat 236      重复测试：每创建一轮，查看该轮任务

五轮完成后，明确指定本轮所有ID再汇总：
  tc42-case5.sh repeat-summary 236 237 238 239 240

也可使用参数形式：
  tc42-case5.sh --stage multi --task-id 233
  tc42-case5.sh --stage single --task-id 234
  tc42-case5.sh --stage repeat --task-id 236
  tc42-case5.sh --stage repeat-summary --repeat-tasks "236 237 238 239 240"

测试前快照：tc42-case5.sh --stage before
附加选项：--log-since 1h、--output-dir 目录、--dry-run。
编号是已核对的历史示例；现场填写刚从前端获得的实际ID。每次查询均核对模式和数据集。
HELP
}
case "${1:-}" in
  --help|'') usage; exit 0 ;;
  multi|single|repeat)
    stage="$1"
    shift
    (($#)) && [[ "$1" =~ ^[1-9][0-9]*$ ]] || { usage >&2; exit 2; }
    task_id="$1"
    shift
    exec "$SCRIPT_DIR/tc42-capture.sh" 5 --stage "$stage" --task-id "$task_id" "$@" ;;
  repeat-summary)
    shift
    ids=()
    while (($#)) && [[ "$1" != --* ]]; do
      [[ "$1" =~ ^[1-9][0-9]*$ ]] || { usage >&2; exit 2; }
      ids+=("$1")
      shift
    done
    ((${#ids[@]} >= 5)) || { usage >&2; exit 2; }
    exec "$SCRIPT_DIR/tc42-capture.sh" 5 --stage repeat-summary --repeat-tasks "${ids[*]}" "$@" ;;
  *) exec "$SCRIPT_DIR/tc42-capture.sh" 5 "$@" ;;
esac

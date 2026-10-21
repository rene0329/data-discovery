#!/usr/bin/env bash
# Start this in a second ZJ terminal before the platform operation.
set -euo pipefail
umask 077
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SINCE=1m
OUTPUT_ROOT="${OUTPUT_ROOT:-/data/topic4-build/evidence/tc42-captures}"
while (($#)); do
  case "$1" in
    --since|--output-dir)
      (($# >= 2)) || { echo "$1 缺少参数" >&2; exit 2; }
      if [[ "$1" == --since ]]; then SINCE="$2"; else OUTPUT_ROOT="$2"; fi
      shift 2 ;;
    --help)
      echo '用法：tc42-live-logs.sh [--since 1m] [--output-dir 目录]'
      echo '在另一个ZJ终端中启动，随后在平台创建任务或迁移；Ctrl+C停止，日志持续保存。'
      exit 0 ;;
    *) echo "未知选项：$1" >&2; exit 2 ;;
  esac
done
[[ "$SINCE" =~ ^[1-9][0-9]*[smhd]$ ]] || { echo '日志范围应类似1m或1h' >&2; exit 2; }
mkdir -p "$OUTPUT_ROOT"
RUN_DIR="$(mktemp -d "${OUTPUT_ROOT%/}/live-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
echo "实时调度日志：$RUN_DIR/scheduling.txt；Ctrl+C停止。"
"$SCRIPT_DIR/show-tc42-evidence.sh" logs --since "$SINCE" --follow 2>&1 | tee "$RUN_DIR/scheduling.txt"

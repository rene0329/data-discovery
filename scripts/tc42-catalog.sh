#!/usr/bin/env bash
# Current business dataset catalog, one row per dataset/version.
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/show-tc42-evidence.sh"
if (($#)); then
  echo '用法：./scripts/tc42-catalog.sh'
  exit 1
fi
export PYTHONIOENCODING=utf-8
WIDTH="${TC42_TABLE_WIDTH:-$(tput cols 2>/dev/null || echo 110)}"
[[ "$WIDTH" =~ ^[0-9]+$ ]] || WIDTH=110
backend_init
echo '【业务数据集概览】名称、描述、版本、单份数据大小'
mysql_xml "SELECT d.name,NULLIF(TRIM(d.description),'') AS description,d.dataset_version AS version,COALESCE(m.authoritative_size_bytes,r.size_bytes) AS size_bytes FROM registered_dataset d LEFT JOIN dataset_metadata m ON m.dataset_id=d.dataset_id LEFT JOIN (SELECT dataset_id,MAX(size_bytes) AS size_bytes FROM dataset_replica WHERE availability='AVAILABLE' GROUP BY dataset_id) r ON r.dataset_id=d.dataset_id WHERE d.deleted_at IS NULL ORDER BY d.dataset_id;" |
  python3 -B "$SCRIPT_DIR/tc42_dataset_overview.py" catalog --width "$WIDTH"

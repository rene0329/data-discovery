#!/usr/bin/env bash
# Read-only overview of current dataset heat and replica locations.
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/show-tc42-evidence.sh"
if (($#)); then
  echo '用法：./scripts/tc42-datasets.sh'
  exit 1
fi
export TC42_TABLE_LAYOUT=compact TC42_TABLE_WIDTH="${TC42_TABLE_WIDTH:-$(tput cols 2>/dev/null || echo 120)}" PYTHONIOENCODING=utf-8
backend_init
print_metadata
echo '【所有数据集：当前热度】'
mysql_table "SELECT dataset_id,dataset_code,name,data_heat,heat_updated_at FROM registered_dataset WHERE deleted_at IS NULL ORDER BY dataset_id;"
echo '【所有数据集：存储位置与副本状态】'
mysql_table "SELECT d.dataset_id,r.replica_id,n.node_name,n.site_code,n.type AS node_type,r.availability,r.size_bytes FROM registered_dataset d LEFT JOIN dataset_replica r ON r.dataset_id=d.dataset_id LEFT JOIN node_management n ON n.node_id=r.node_id WHERE d.deleted_at IS NULL ORDER BY d.dataset_id,r.replica_id;"

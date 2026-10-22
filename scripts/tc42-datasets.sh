#!/usr/bin/env bash
# Read-only overview of current dataset heat and replica locations.
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/show-tc42-evidence.sh"
if (($#)); then
  echo '用法：./scripts/tc42-datasets.sh'
  exit 1
fi
export PYTHONIOENCODING=utf-8
WIDTH="${TC42_TABLE_WIDTH:-$(tput cols 2>/dev/null || echo 110)}"
[[ "$WIDTH" =~ ^[0-9]+$ ]] || WIDTH=110
backend_init
echo "当前环境：$(date '+%Y-%m-%d %H:%M:%S %Z')"
echo '【当前业务数据与存储分布】'
mysql_xml "SELECT d.name,d.data_heat,n.node_name,n.site_code,r.availability,r.size_bytes FROM registered_dataset d LEFT JOIN dataset_replica r ON r.dataset_id=d.dataset_id AND (r.availability IS NULL OR r.availability<>'MISSING') LEFT JOIN node_management n ON n.node_id=r.node_id WHERE d.deleted_at IS NULL ORDER BY d.dataset_id,r.replica_id;" |
  python3 -B "$SCRIPT_DIR/tc42_dataset_overview.py" datasets --width "$WIDTH"
echo '【当前存储与计算资源分布】'
echo 'cpu：可用/总量（核）；mem_GiB：可用/总量；replicas：AVAILABLE 副本数。'
mysql_xml "SELECT n.node_name,n.site_code,n.type,n.max_cpu,n.current_cpu,n.max_memory,n.current_memory,COALESCE(r.replicas,0) AS replicas,CASE WHEN n.registration_status<>'ACTIVE' THEN n.registration_status WHEN n.enabled=0 THEN 'DISABLED' ELSE n.observed_status END AS status FROM node_management n LEFT JOIN (SELECT r.node_id,COUNT(*) AS replicas FROM dataset_replica r JOIN registered_dataset d ON d.dataset_id=r.dataset_id WHERE d.deleted_at IS NULL AND r.availability='AVAILABLE' GROUP BY r.node_id) r ON r.node_id=n.node_id WHERE n.deleted_at IS NULL ORDER BY n.site_code,n.node_id;" |
  python3 -B "$SCRIPT_DIR/tc42_dataset_overview.py" nodes --width "$WIDTH"

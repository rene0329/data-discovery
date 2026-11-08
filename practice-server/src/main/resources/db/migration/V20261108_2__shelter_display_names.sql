-- Display names only: keep domain IDs/codes, site mappings and permissions intact.
-- Run explicitly after V20260924_3; this application does not auto-run migrations.
-- Preserve custom administrator names and historical task/audit snapshots.
SET NAMES utf8mb4;
START TRANSACTION;
UPDATE collaboration_domain SET name = '上海方舱'
WHERE site_code = 'sh' AND name = '上海域（A）';
UPDATE collaboration_domain SET name = '深圳方舱'
WHERE site_code = 'sz' AND name = '深圳域（B）';
UPDATE collaboration_domain SET name = '北京方舱'
WHERE site_code = 'bj' AND name = '北京域（C）';
UPDATE collaboration_domain SET name = '杭州方舱'
WHERE site_code = 'hz' AND name = '杭州域（D）';
UPDATE collaboration_domain SET name = '中心方舱'
WHERE site_code = 'core' AND name = '中心域';
COMMIT;

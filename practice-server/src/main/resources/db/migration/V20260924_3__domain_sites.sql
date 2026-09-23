-- A collaboration domain is a set of nodes: every registered node whose
-- node_management.site_code (V20260924_1) equals the domain's site_code. A
-- dataset belongs to the domain(s) of the nodes currently holding its replicas,
-- so its domain follows copy/move scheduling automatically; see
-- DatasetDomainMapper for the location rule the usage policy applies.
-- One domain per site (UNIQUE); a domain without a site holds no datasets.
-- Every statement is guarded so the script can be re-run after a partial
-- operational apply, like the other migrations. Depends on V20260920_1
-- (collaboration_domain) and V20260924_1 (node_management.site_code).
SET @db = DATABASE();

-- site_code is joined against node_management.site_code, whose table uses
-- utf8mb4_general_ci while collaboration_domain defaults to utf8mb4_0900_ai_ci;
-- comparing the two fails with "Illegal mix of collations", so the column is
-- pinned to node_management's collation (also on databases where an earlier
-- run of this script already added it with the table default).
SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='collaboration_domain' AND column_name='site_code'),
  'SELECT 1', 'ALTER TABLE collaboration_domain ADD COLUMN site_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL AFTER name'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='collaboration_domain' AND column_name='site_code'
    AND collation_name <> 'utf8mb4_general_ci'),
  'ALTER TABLE collaboration_domain MODIFY site_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.statistics
  WHERE table_schema=@db AND table_name='collaboration_domain' AND index_name='uk_collaboration_domain_site'),
  'SELECT 1', 'ALTER TABLE collaboration_domain ADD UNIQUE KEY uk_collaboration_domain_site (site_code)'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Map the four business domains onto their sites. Only rows that have no site
-- yet are touched, so re-running never overwrites a later admin edit, and a
-- site already held by another domain is skipped instead of failing the unique
-- key (the self-join is the MySQL-legal way to look at the table being updated).
UPDATE collaboration_domain d LEFT JOIN collaboration_domain taken ON taken.site_code = 'sh'
SET d.site_code = 'sh', d.name = '上海域（A）'
WHERE d.domain_code = 'domain-a' AND d.site_code IS NULL AND taken.domain_id IS NULL;

UPDATE collaboration_domain d LEFT JOIN collaboration_domain taken ON taken.site_code = 'sz'
SET d.site_code = 'sz', d.name = '深圳域（B）'
WHERE d.domain_code = 'domain-b' AND d.site_code IS NULL AND taken.domain_id IS NULL;

UPDATE collaboration_domain d LEFT JOIN collaboration_domain taken ON taken.site_code = 'bj'
SET d.site_code = 'bj', d.name = '北京域（C）'
WHERE d.domain_code = 'domain-c' AND d.site_code IS NULL AND taken.domain_id IS NULL;

UPDATE collaboration_domain d LEFT JOIN collaboration_domain taken ON taken.site_code = 'hz'
SET d.site_code = 'hz', d.name = '杭州域（D）'
WHERE d.domain_code = 'domain-d' AND d.site_code IS NULL AND taken.domain_id IS NULL;

-- The three ZJ masters (site 'core') form the 中心域.
INSERT INTO collaboration_domain (domain_code, name, site_code, description, enabled)
SELECT 'domain-center', '中心域', 'core', 'master-40、master-141、master-215', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM collaboration_domain
                  WHERE domain_code = 'domain-center' OR site_code = 'core');

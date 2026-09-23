-- "In-place" (原位) scheduling needs a physical-site grouping that is coarser
-- than node_id and independent of node type (compute vs storage), so a
-- storage-only replica node and a compute node in the same site both count as
-- "the data's location". No such column exists today: node_management.cluster
-- is the k8s API cluster (one value for the whole fleet), not a site.
SET @db = DATABASE();

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='node_management' AND column_name='site_code'),
  'SELECT 1', 'ALTER TABLE node_management ADD COLUMN site_code VARCHAR(32) NULL AFTER cluster'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Backfill from the existing naming convention: cluster-<site>-<n> for the
-- Aliyun edge nodes, 'core' for the three ungrouped ZJ masters. Anything not
-- matching either pattern is left NULL rather than guessed, so it shows up as
-- an explicit gap (see NodeManagement.siteCode) for an operator to set by hand
-- through node registration/update rather than being silently mis-tagged.
UPDATE node_management
SET site_code = LOWER(SUBSTRING_INDEX(SUBSTRING_INDEX(node_name, '-', 2), '-', -1))
WHERE site_code IS NULL AND node_name REGEXP '^cluster-[a-zA-Z]+-[0-9]+$';

UPDATE node_management
SET site_code = 'core'
WHERE site_code IS NULL AND node_name REGEXP '^master-[0-9]+$';

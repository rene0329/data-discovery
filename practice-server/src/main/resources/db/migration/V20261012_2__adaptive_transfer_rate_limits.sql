-- Adaptive transfer throttling. The current configuration can derive a rate
-- from recent network measurements while retaining the manual rules as a
-- safe fallback when measurements are missing or stale.

SET @db = DATABASE();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='data_transfer_rate_limit_config'
    AND column_name='adaptive_enabled') = 0,
  'ALTER TABLE data_transfer_rate_limit_config ADD COLUMN adaptive_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER enabled',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='data_transfer_rate_limit_config'
    AND column_name='adaptive_safety_factor') = 0,
  'ALTER TABLE data_transfer_rate_limit_config ADD COLUMN adaptive_safety_factor DECIMAL(5,3) NOT NULL DEFAULT 0.800 AFTER target_max',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='data_transfer_rate_limit_config'
    AND column_name='measurement_max_age_seconds') = 0,
  'ALTER TABLE data_transfer_rate_limit_config ADD COLUMN measurement_max_age_seconds INT NOT NULL DEFAULT 900 AFTER adaptive_safety_factor',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='data_transfer_rate_limit_config'
    AND column_name='adaptive_central_node') = 0,
  'ALTER TABLE data_transfer_rate_limit_config ADD COLUMN adaptive_central_node VARCHAR(128) NOT NULL DEFAULT ''master-40'' AFTER measurement_max_age_seconds',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='data_transfer_rate_limit_history'
    AND column_name='adaptive_enabled') = 0,
  'ALTER TABLE data_transfer_rate_limit_history ADD COLUMN adaptive_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER enabled',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='data_transfer_rate_limit_history'
    AND column_name='adaptive_safety_factor') = 0,
  'ALTER TABLE data_transfer_rate_limit_history ADD COLUMN adaptive_safety_factor DECIMAL(5,3) NOT NULL DEFAULT 0.800 AFTER target_max',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='data_transfer_rate_limit_history'
    AND column_name='measurement_max_age_seconds') = 0,
  'ALTER TABLE data_transfer_rate_limit_history ADD COLUMN measurement_max_age_seconds INT NOT NULL DEFAULT 900 AFTER adaptive_safety_factor',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='data_transfer_rate_limit_history'
    AND column_name='adaptive_central_node') = 0,
  'ALTER TABLE data_transfer_rate_limit_history ADD COLUMN adaptive_central_node VARCHAR(128) NOT NULL DEFAULT ''master-40'' AFTER measurement_max_age_seconds',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- The first runtime-editable configuration was seeded by the preceding
-- migration. Promote that exact seed to adaptive mode once and preserve both
-- versions in the audit history. Re-running this file does not create another
-- version.
UPDATE data_transfer_rate_limit_config
SET version = 2,
    adaptive_enabled = 1,
    adaptive_safety_factor = 0.800,
    measurement_max_age_seconds = 900,
    adaptive_central_node = 'master-40',
    updated_by = 'adaptive-migration',
    updated_at = CURRENT_TIMESTAMP(3)
WHERE config_id = 1 AND version = 1 AND updated_by = 'migration';

INSERT IGNORE INTO data_transfer_rate_limit_history
    (version, enabled, adaptive_enabled, default_rate, path_rules,
     target_min, target_max, adaptive_safety_factor,
     measurement_max_age_seconds, adaptive_central_node,
     changed_by, changed_at)
SELECT version, enabled, adaptive_enabled, default_rate, path_rules,
       target_min, target_max, adaptive_safety_factor,
       measurement_max_age_seconds, adaptive_central_node,
       updated_by, updated_at
FROM data_transfer_rate_limit_config
WHERE config_id = 1;

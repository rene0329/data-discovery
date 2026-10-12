-- Active bandwidth probes are opt-in because iperf3 can compete with normal
-- data transfers. Existing and newly initialized configurations stay off until
-- an administrator enables the switch on the internal rate-limit page.

SET @db = DATABASE();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='data_transfer_rate_limit_config'
    AND column_name='bandwidth_probe_enabled') = 0,
  'ALTER TABLE data_transfer_rate_limit_config ADD COLUMN bandwidth_probe_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER adaptive_enabled',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='data_transfer_rate_limit_history'
    AND column_name='bandwidth_probe_enabled') = 0,
  'ALTER TABLE data_transfer_rate_limit_history ADD COLUMN bandwidth_probe_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER adaptive_enabled',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Record the new opt-in control as a distinct configuration version once.
UPDATE data_transfer_rate_limit_config
SET version = 3,
    bandwidth_probe_enabled = 0,
    updated_by = 'probe-control-migration',
    updated_at = CURRENT_TIMESTAMP(3)
WHERE config_id = 1 AND version = 2 AND updated_by = 'adaptive-migration';

INSERT IGNORE INTO data_transfer_rate_limit_history
    (version, enabled, adaptive_enabled, bandwidth_probe_enabled,
     default_rate, path_rules, target_min, target_max,
     adaptive_safety_factor, measurement_max_age_seconds,
     adaptive_central_node, changed_by, changed_at)
SELECT version, enabled, adaptive_enabled, bandwidth_probe_enabled,
       default_rate, path_rules, target_min, target_max,
       adaptive_safety_factor, measurement_max_age_seconds,
       adaptive_central_node, updated_by, updated_at
FROM data_transfer_rate_limit_config
WHERE config_id = 1;

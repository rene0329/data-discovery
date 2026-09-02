-- Heat belongs to a logical dataset, not to a filename or a replica.
-- Seed migrated datasets through their explicit legacy ID; new datasets start at 10.
SET @db = DATABASE();
SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'registered_dataset' AND column_name = 'data_heat'),
    'SELECT 1', 'ALTER TABLE registered_dataset ADD COLUMN data_heat DOUBLE NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'registered_dataset' AND column_name = 'heat_updated_at'),
    'SELECT 1', 'ALTER TABLE registered_dataset ADD COLUMN heat_updated_at DATETIME(3) NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE registered_dataset r LEFT JOIN data_management d ON d.data_id = r.legacy_data_id
SET r.data_heat = COALESCE(d.data_heat, 10),
    -- Legacy heat uses CURRENT_TIMESTAMP (database local time); the logical catalog uses UTC.
    r.heat_updated_at = COALESCE(TIMESTAMPADD(SECOND,
        TIMESTAMPDIFF(SECOND, CURRENT_TIMESTAMP(3), UTC_TIMESTAMP(3)), d.heat_updated_at), UTC_TIMESTAMP(3)),
    r.updated_at = r.updated_at
WHERE r.data_heat IS NULL;
UPDATE registered_dataset SET heat_updated_at = UTC_TIMESTAMP(3), updated_at = updated_at
WHERE heat_updated_at IS NULL;
ALTER TABLE registered_dataset
    MODIFY COLUMN data_heat DOUBLE NOT NULL DEFAULT 10,
    MODIFY COLUMN heat_updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

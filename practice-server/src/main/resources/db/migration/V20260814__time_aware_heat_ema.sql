-- 热度衰减改为按真实经过时长计算。该字段只表示上一次热度折算时间，
-- 不复用文件的 last_modified_time。
SET @db = DATABASE();

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = @db
        AND table_name = 'data_management'
        AND column_name = 'heat_updated_at'
    ),
    'SELECT 1',
    'ALTER TABLE data_management ADD COLUMN heat_updated_at DATETIME(3) NULL AFTER data_count'
  )
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE data_management
SET heat_updated_at = CURRENT_TIMESTAMP(3)
WHERE heat_updated_at IS NULL;

ALTER TABLE data_management
  MODIFY COLUMN heat_updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

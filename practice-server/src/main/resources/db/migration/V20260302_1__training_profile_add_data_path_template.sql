-- 给已创建的 training_profile 补充 data_path_template（幂等）
SET @db = DATABASE();

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = @db
        AND table_name = 'training_profile'
        AND column_name = 'data_path_template'
    ),
    'SELECT 1',
    'ALTER TABLE training_profile ADD COLUMN data_path_template VARCHAR(255) NOT NULL DEFAULT ''/data/{dataset}'' AFTER entrypoint'
  )
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 为历史记录补全默认模板
UPDATE training_profile
SET data_path_template = '/data/{dataset}'
WHERE id > 0
  AND (data_path_template IS NULL OR data_path_template = '');

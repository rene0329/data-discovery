-- 新版任务显式引用注册资源；保留 selected_data 以兼容旧页面和历史调度接口。
SET @db = DATABASE();

SET @sql = (
  SELECT IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'task_management' AND column_name = 'dataset_ids_json'
  ), 'SELECT 1', 'ALTER TABLE task_management ADD COLUMN dataset_ids_json TEXT NULL AFTER selected_data')
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (
  SELECT IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'task_management' AND column_name = 'runtime_image_id'
  ), 'SELECT 1', 'ALTER TABLE task_management ADD COLUMN runtime_image_id BIGINT NULL AFTER dataset_ids_json')
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'task_management' AND column_name = 'resource_overrides_json'
  ), 'SELECT 1', 'ALTER TABLE task_management ADD COLUMN resource_overrides_json TEXT NULL AFTER runtime_image_id')
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'migration_task' AND column_name = 'registered_dataset_id'
  ), 'SELECT 1', 'ALTER TABLE migration_task ADD COLUMN registered_dataset_id BIGINT NULL AFTER data_id')
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 新注册的数据集不一定存在旧 data_management 记录。
SET @sql = (
  SELECT IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'migration_task' AND column_name = 'data_id' AND is_nullable = 'NO'
  ), 'ALTER TABLE migration_task MODIFY COLUMN data_id INT NULL', 'SELECT 1')
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

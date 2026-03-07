-- 适用于“之前版本已执行过”的补丁脚本（幂等）
-- 目标：避免重复执行报错，并将 file_path 索引修正为前缀索引。

SET @db = DATABASE();

-- =========================
-- 1) data_management 补列（若缺失）
-- =========================

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'data_management' AND column_name = 'file_path'
    ),
    'SELECT 1',
    'ALTER TABLE data_management ADD COLUMN file_path VARCHAR(1024) NULL'
  )
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'data_management' AND column_name = 'last_modified_time'
    ),
    'SELECT 1',
    'ALTER TABLE data_management ADD COLUMN last_modified_time TIMESTAMP NULL'
  )
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'data_management' AND column_name = 'file_type'
    ),
    'SELECT 1',
    'ALTER TABLE data_management ADD COLUMN file_type VARCHAR(64) NULL'
  )
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'data_management' AND column_name = 'md5_hash'
    ),
    'SELECT 1',
    'ALTER TABLE data_management ADD COLUMN md5_hash VARCHAR(64) NULL'
  )
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = @db AND table_name = 'data_management' AND column_name = 'data_node_id'
    ),
    'SELECT 1',
    'ALTER TABLE data_management ADD COLUMN data_node_id INT NULL'
  )
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =========================
-- 2) 索引修复（幂等）
-- =========================

-- data_node_id 索引
SET @sql = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = @db AND table_name = 'data_management' AND index_name = 'idx_data_management_data_node_id'
    ),
    'SELECT 1',
    'ALTER TABLE data_management ADD INDEX idx_data_management_data_node_id (data_node_id)'
  )
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- file_path 索引：强制为前缀索引(255)
-- 若同名索引已存在但不是前缀索引，先删后建。
SET @idx_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = @db AND table_name = 'data_management' AND index_name = 'idx_data_management_file_path'
);

SET @idx_sub_part = (
  SELECT MIN(sub_part) FROM information_schema.statistics
  WHERE table_schema = @db AND table_name = 'data_management' AND index_name = 'idx_data_management_file_path'
);

SET @sql = (
  SELECT IF(
    @idx_exists = 0,
    'ALTER TABLE data_management ADD INDEX idx_data_management_file_path (file_path(255))',
    IF(
      @idx_sub_part = 255,
      'SELECT 1',
      'ALTER TABLE data_management DROP INDEX idx_data_management_file_path, ADD INDEX idx_data_management_file_path (file_path(255))'
    )
  )
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =========================
-- 3) migration_task / data_replica（若缺失则创建）
-- =========================

CREATE TABLE IF NOT EXISTS migration_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id INT NULL COMMENT '关联 task_management.task_id',
    data_id INT NOT NULL COMMENT '关联 data_management.data_id',
    source_node_id INT NOT NULL,
    target_node_id INT NOT NULL,
    status VARCHAR(32) NOT NULL COMMENT 'PLANNED/COPYING/VERIFYING/SWITCHING/COMPLETED/FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1024) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    checksum_before VARCHAR(64) NULL,
    checksum_after VARCHAR(64) NULL,
    bytes_total BIGINT NULL,
    bytes_done BIGINT NULL,
    INDEX idx_migration_task_task_id (task_id),
    INDEX idx_migration_task_data_id (data_id),
    INDEX idx_migration_task_status (status)
);

CREATE TABLE IF NOT EXISTS data_replica (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_id INT NOT NULL,
    node_id INT NOT NULL,
    replica_role VARCHAR(16) NOT NULL COMMENT 'PRIMARY/SECONDARY',
    file_path VARCHAR(1024) NULL,
    checksum VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/STAGING/STALE',
    last_verified_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_data_replica_data_node (data_id, node_id),
    INDEX idx_data_replica_data_id (data_id),
    INDEX idx_data_replica_node_id (node_id)
);

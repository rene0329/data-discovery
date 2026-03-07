-- 为“热敏制导存储 / 数据原位汇聚 / 数据勘探”准备的数据库变更建议
-- 执行前请先在测试环境验证，并做好备份。

-- =========================
-- 1) 现有功能最小稳定性增强
-- =========================

-- 1.1 data_management：按 data_name 更新/查找较多，建议唯一索引
ALTER TABLE data_management
    ADD UNIQUE INDEX uk_data_management_data_name (data_name);

-- 1.2 edge_management：带宽/延迟建议改为数值类型（便于计算与排序）
-- 若历史数据为字符串，请先做清洗再执行类型变更。
ALTER TABLE edge_management
    MODIFY COLUMN bandwidth BIGINT NULL,
    MODIFY COLUMN latency DOUBLE NULL;

-- =========================
-- 2) 数据勘探（File Discovery）所需字段
-- =========================
-- 如果你已决定启用 data-discovery-daemonset-service，请补齐以下字段。
-- （当前代码里已使用这些字段）

ALTER TABLE data_management
    ADD COLUMN file_path VARCHAR(1024) NULL,
    ADD COLUMN last_modified_time TIMESTAMP NULL,
    ADD COLUMN file_type VARCHAR(64) NULL,
    ADD COLUMN md5_hash VARCHAR(64) NULL,
    ADD COLUMN data_node_id INT NULL;

ALTER TABLE data_management
    ADD INDEX idx_data_management_data_node_id (data_node_id);

-- 注意：file_path 为 VARCHAR(1024)，在 utf8mb4 下全列索引会超出 InnoDB 索引长度限制。
-- 采用前缀索引（255）兼顾查询能力与兼容性。
ALTER TABLE data_management
    ADD INDEX idx_data_management_file_path (file_path(255));

-- 可选：建立外键（若存在历史脏数据，先清洗）
-- ALTER TABLE data_management
--     ADD CONSTRAINT fk_data_management_data_node
--     FOREIGN KEY (data_node_id) REFERENCES node_management(node_id)
--     ON UPDATE CASCADE ON DELETE SET NULL;

-- =========================
-- 3) 迁移任务编排（Migration Task）
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

-- =========================
-- 4) 数据副本表（推荐）
-- =========================
-- 用于记录“一个数据在多个节点上的副本”，比仅用 data_server 更可扩展。

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

-- 可选外键
-- ALTER TABLE data_replica
--     ADD CONSTRAINT fk_data_replica_data
--     FOREIGN KEY (data_id) REFERENCES data_management(data_id)
--     ON UPDATE CASCADE ON DELETE CASCADE,
--     ADD CONSTRAINT fk_data_replica_node
--     FOREIGN KEY (node_id) REFERENCES node_management(node_id)
--     ON UPDATE CASCADE ON DELETE CASCADE;

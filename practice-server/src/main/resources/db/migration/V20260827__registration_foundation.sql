-- 节点、数据集、运行镜像注册能力基础表。
-- 设计原则：自动发现只写 candidate/observation，注册资源才进入调度主表。

CREATE TABLE IF NOT EXISTS node_discovery_candidate (
    candidate_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cluster_id VARCHAR(128) NOT NULL,
    k8s_uid VARCHAR(128) NOT NULL,
    k8s_node_name VARCHAR(255) NOT NULL,
    internal_ip VARCHAR(64) NULL,
    external_ip VARCHAR(64) NULL,
    observed_role VARCHAR(64) NULL,
    max_cpu DOUBLE NULL,
    max_memory DOUBLE NULL COMMENT 'Gi',
    current_cpu DOUBLE NULL,
    current_memory DOUBLE NULL COMMENT 'Gi',
    observed_status VARCHAR(32) NOT NULL DEFAULT 'ONLINE',
    registered_node_id INT NULL,
    labels_json TEXT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_node_candidate_cluster_uid (cluster_id, k8s_uid),
    INDEX idx_node_candidate_cluster_name (cluster_id, k8s_node_name),
    INDEX idx_node_candidate_status (observed_status),
    INDEX idx_node_candidate_registered (registered_node_id)
);

-- 兼容已经存在的 node_management。以下字段将人工注册状态和 K8s 观测状态分开。
SET @schema_name = DATABASE();

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='node_management' AND column_name='display_name'),
    'SELECT 1',
    'ALTER TABLE node_management ADD COLUMN display_name VARCHAR(128) NULL AFTER node_name'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='node_management' AND column_name='k8s_uid'),
    'SELECT 1',
    'ALTER TABLE node_management ADD COLUMN k8s_uid VARCHAR(128) NULL AFTER cluster'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='node_management' AND column_name='registration_status'),
    'SELECT 1',
    'ALTER TABLE node_management ADD COLUMN registration_status VARCHAR(32) NOT NULL DEFAULT ''ACTIVE'''
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='node_management' AND column_name='enabled'),
    'SELECT 1',
    'ALTER TABLE node_management ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='node_management' AND column_name='labels_json'),
    'SELECT 1',
    'ALTER TABLE node_management ADD COLUMN labels_json TEXT NULL'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='node_management' AND column_name='last_seen_at'),
    'SELECT 1',
    'ALTER TABLE node_management ADD COLUMN last_seen_at DATETIME(3) NULL'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='node_management' AND column_name='verified_at'),
    'SELECT 1',
    'ALTER TABLE node_management ADD COLUMN verified_at DATETIME(3) NULL'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='node_management' AND column_name='deleted_at'),
    'SELECT 1',
    'ALTER TABLE node_management ADD COLUMN deleted_at DATETIME(3) NULL'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='node_management' AND column_name='row_version'),
    'SELECT 1',
    'ALTER TABLE node_management ADD COLUMN row_version INT NOT NULL DEFAULT 0'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE node_management
SET display_name = COALESCE(display_name, node_name),
    registration_status = COALESCE(NULLIF(registration_status, ''), 'ACTIVE'),
    enabled = COALESCE(enabled, 1),
    last_seen_at = COALESCE(last_seen_at, last_update_time)
WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS dataset_discovery_candidate (
    candidate_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    node_id INT NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(64) NULL,
    size_bytes BIGINT NOT NULL,
    checksum VARCHAR(128) NULL,
    last_modified_at DATETIME(3) NULL,
    availability VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
    registered_dataset_id BIGINT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_dataset_candidate_node_path (node_id, file_path(512)),
    INDEX idx_dataset_candidate_status (availability),
    INDEX idx_dataset_candidate_registered (registered_dataset_id),
    INDEX idx_dataset_candidate_name (file_name)
);

CREATE TABLE IF NOT EXISTS runtime_image (
    runtime_image_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    legacy_profile_id VARCHAR(64) NULL,
    name VARCHAR(128) NOT NULL,
    image_ref VARCHAR(512) NOT NULL,
    resolved_digest VARCHAR(255) NULL,
    task_type VARCHAR(64) NOT NULL,
    model_type VARCHAR(64) NOT NULL,
    command_json TEXT NOT NULL,
    args_template_json TEXT NULL,
    data_path_template VARCHAR(255) NOT NULL DEFAULT '/data/{dataset}',
    default_cpu DOUBLE NULL,
    default_memory_gi DOUBLE NULL,
    default_gpu DOUBLE NULL,
    pull_secret_ref VARCHAR(253) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    verified_at DATETIME(3) NULL,
    verification_message VARCHAR(1024) NULL,
    row_version INT NOT NULL DEFAULT 0,
    deleted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_runtime_image_name (name),
    UNIQUE KEY uk_runtime_image_legacy_profile (legacy_profile_id),
    INDEX idx_runtime_image_task_type (task_type),
    INDEX idx_runtime_image_status (status, enabled)
);

CREATE TABLE IF NOT EXISTS registered_dataset (
    dataset_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    legacy_data_id INT NULL,
    dataset_code VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    dataset_version VARCHAR(64) NOT NULL DEFAULT '1.0',
    description TEXT NULL,
    data_type VARCHAR(64) NOT NULL,
    labels_json TEXT NULL,
    required_cpu DOUBLE NULL,
    required_memory_gi DOUBLE NULL,
    required_gpu DOUBLE NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    default_runtime_image_id BIGINT NULL,
    verified_at DATETIME(3) NULL,
    verification_message VARCHAR(1024) NULL,
    row_version INT NOT NULL DEFAULT 0,
    deleted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_registered_dataset_code_version (dataset_code, dataset_version),
    UNIQUE KEY uk_registered_dataset_legacy_data (legacy_data_id),
    INDEX idx_registered_dataset_status (status),
    INDEX idx_registered_dataset_image (default_runtime_image_id)
);

CREATE TABLE IF NOT EXISTS dataset_replica (
    replica_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    node_id INT NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    size_bytes BIGINT NULL,
    checksum VARCHAR(128) NULL,
    availability VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
    last_seen_at DATETIME(3) NULL,
    verified_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_dataset_replica_dataset_node_path (dataset_id, node_id, file_path(384)),
    INDEX idx_dataset_replica_node (node_id),
    INDEX idx_dataset_replica_availability (availability)
);

CREATE TABLE IF NOT EXISTS registration_audit_log (
    audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64) NULL,
    action VARCHAR(64) NOT NULL,
    actor VARCHAR(128) NULL,
    request_id VARCHAR(128) NULL,
    detail_json TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_registration_audit_resource (resource_type, resource_id),
    INDEX idx_registration_audit_request (request_id)
);

-- 现有训练配置作为兼容镜像导入。入口命令采用 JSON 字符串保存，凭据不迁移。
INSERT INTO runtime_image (
    legacy_profile_id, name, image_ref, task_type, model_type,
    command_json, data_path_template, default_cpu, default_memory_gi,
    status, enabled, verified_at
)
SELECT
    p.profile_id,
    p.profile_id,
    p.image,
    p.task_type,
    p.model_type,
    CONCAT('["', REPLACE(p.entrypoint, '"', '\\"'), '"]'),
    p.data_path_template,
    p.default_cpu,
    p.default_mem,
    CASE WHEN p.active = 1 THEN 'READY' ELSE 'DISABLED' END,
    p.active,
    CASE WHEN p.active = 1 THEN CURRENT_TIMESTAMP(3) ELSE NULL END
FROM training_profile p
WHERE NOT EXISTS (
    SELECT 1 FROM runtime_image ri WHERE ri.legacy_profile_id = p.profile_id
);

-- 现有 data_management 记录作为已验证数据集迁移，保持升级前可选择性。
INSERT INTO registered_dataset (
    legacy_data_id, dataset_code, name, dataset_version, description, data_type,
    required_cpu, required_memory_gi, status, verified_at
)
SELECT
    d.data_id,
    LOWER(REPLACE(d.data_name, ' ', '-')),
    d.data_name,
    '1.0',
    d.data_description,
    COALESCE(NULLIF(d.file_type, ''), 'UNKNOWN'),
    d.requiredCpu,
    d.requiredMemory,
    CASE WHEN COALESCE(d.data_status, 1) = 1 THEN 'ACTIVE' ELSE 'DISABLED' END,
    CURRENT_TIMESTAMP(3)
FROM data_management d
WHERE d.data_name IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM registered_dataset rd WHERE rd.legacy_data_id = d.data_id
  );

INSERT INTO dataset_replica (
    dataset_id, node_id, file_path, size_bytes, checksum, availability, last_seen_at, verified_at
)
SELECT
    rd.dataset_id,
    d.data_node_id,
    d.file_path,
    d.data_size,
    d.md5_hash,
    'AVAILABLE',
    COALESCE(d.last_modified_time, CURRENT_TIMESTAMP(3)),
    CURRENT_TIMESTAMP(3)
FROM registered_dataset rd
JOIN data_management d ON d.data_id = rd.legacy_data_id
WHERE d.data_node_id IS NOT NULL
  AND d.file_path IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM dataset_replica r
      WHERE r.dataset_id = rd.dataset_id
        AND r.node_id = d.data_node_id
        AND BINARY r.file_path = BINARY d.file_path
  );

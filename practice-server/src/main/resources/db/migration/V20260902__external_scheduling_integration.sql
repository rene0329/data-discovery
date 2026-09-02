-- 外部调度系统最小可运行数据模型。

ALTER TABLE dataset_discovery_candidate
    ADD COLUMN metadata_json LONGTEXT NULL AFTER checksum;

ALTER TABLE registered_dataset
    ADD COLUMN category VARCHAR(64) NOT NULL DEFAULT 'OTHER' AFTER data_type,
    ADD COLUMN data_format VARCHAR(64) NOT NULL DEFAULT 'NPZ' AFTER category;

UPDATE registered_dataset
SET data_format = COALESCE(NULLIF(data_type, ''), 'NPZ')
WHERE data_format = 'NPZ';

CREATE TABLE dataset_metadata (
    dataset_id BIGINT PRIMARY KEY,
    metadata_version VARCHAR(32) NOT NULL DEFAULT '1.0',
    digest_algorithm VARCHAR(32) NULL,
    digest_value VARCHAR(256) NULL,
    schema_json LONGTEXT NULL,
    profile_json LONGTEXT NULL,
    source_json LONGTEXT NULL,
    scheduling_hints_json LONGTEXT NULL,
    labels_json LONGTEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE scheduling_plan (
    plan_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_plan_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    internal_task_id INT NULL,
    algorithm_name VARCHAR(128) NULL,
    algorithm_version VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACCEPTED',
    error_message VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_scheduling_plan_external (external_plan_id),
    INDEX idx_scheduling_plan_task (task_id),
    INDEX idx_scheduling_plan_status (status)
);

CREATE TABLE scheduling_assignment (
    assignment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    dataset_id BIGINT NOT NULL,
    replica_id BIGINT NOT NULL,
    source_node_id INT NOT NULL,
    target_node_id INT NOT NULL,
    action VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_scheduling_assignment_plan (plan_id),
    INDEX idx_scheduling_assignment_dataset (dataset_id),
    INDEX idx_scheduling_assignment_status (status)
);

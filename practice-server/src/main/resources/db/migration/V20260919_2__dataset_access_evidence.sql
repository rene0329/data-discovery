-- Request-level evidence for heat sensing, replica routing and cache behaviour.
-- A successful, complete business read is the only event that may raise heat.

CREATE TABLE IF NOT EXISTS dataset_access_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NULL,
    task_id INT NULL,
    dataset_id BIGINT NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    consumer_node_id INT NOT NULL,
    source_replica_id BIGINT NULL,
    source_node_id INT NULL,
    cache_layer VARCHAR(32) NULL,
    route_reason VARCHAR(512) NULL,
    expected_bytes BIGINT NULL,
    bytes_read BIGINT NOT NULL DEFAULT 0,
    cache_hit_bytes BIGINT NOT NULL DEFAULT 0,
    checksum VARCHAR(128) NULL,
    started_at DATETIME(3) NOT NULL,
    first_byte_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    duration_ms BIGINT NULL,
    success TINYINT(1) NOT NULL DEFAULT 0,
    failure_reason VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_dataset_access_request (request_id),
    INDEX idx_dataset_access_dataset_time (dataset_id, completed_at),
    INDEX idx_dataset_access_consumer_time (consumer_node_id, completed_at),
    INDEX idx_dataset_access_run (run_id),
    INDEX idx_dataset_access_task (task_id)
);

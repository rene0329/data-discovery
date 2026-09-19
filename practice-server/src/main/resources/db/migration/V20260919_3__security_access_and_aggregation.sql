-- TC-4.2 P3-B/P3-C: scoped data-access evidence and fixed three-party secure sum.

CREATE TABLE IF NOT EXISTS dataset_access_audit_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(128) NULL,
    run_id VARCHAR(128) NULL,
    jti VARCHAR(64) NULL,
    principal VARCHAR(128) NOT NULL,
    dataset_id VARCHAR(128) NULL,
    dataset_version VARCHAR(128) NULL,
    file_path VARCHAR(1024) NULL,
    action VARCHAR(32) NULL,
    target_node VARCHAR(128) NULL,
    policy_rule VARCHAR(256) NULL,
    decision VARCHAR(16) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    token_expires_at DATETIME(3) NULL,
    client_ip VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_dataset_access_request (request_id),
    INDEX idx_dataset_access_run (run_id),
    INDEX idx_dataset_access_principal_time (principal, created_at),
    INDEX idx_dataset_access_jti (jti)
);

CREATE TABLE IF NOT EXISTS secure_aggregation_run (
    run_id VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    protocol_version VARCHAR(64) NOT NULL,
    participants_json VARCHAR(512) NOT NULL,
    final_value VARCHAR(128) NULL,
    failure_reason VARCHAR(1024) NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_secure_aggregation_request (request_id),
    INDEX idx_secure_aggregation_status_time (status, created_at)
);

CREATE TABLE IF NOT EXISTS secure_aggregation_message_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(16) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    message_type VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payload_bytes INT NULL,
    error_code VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_secure_aggregation_event_run (run_id, event_id),
    INDEX idx_secure_aggregation_event_time (created_at)
);

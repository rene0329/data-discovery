-- Privacy-computing control-plane state. Raw data, secret shares, masks and keys are never stored here.
CREATE TABLE IF NOT EXISTS privacy_compute_job (
    job_id VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    current_attempt_id VARCHAR(64) NOT NULL,
    current_attempt_no INT NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    security_profile VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    initiator VARCHAR(64) NOT NULL,
    result_recipients_json TEXT NOT NULL,
    timeout_seconds INT NOT NULL,
    engine_policy_json TEXT NULL,
    spec_json LONGTEXT NOT NULL,
    spec_digest CHAR(64) NOT NULL,
    protocol_version VARCHAR(128) NOT NULL,
    image_digest VARCHAR(128) NOT NULL,
    external_job_id VARCHAR(160) NULL,
    failure_code VARCHAR(128) NULL,
    failure_reason VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    queued_at DATETIME(3) NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_privacy_job_request (request_id),
    INDEX idx_privacy_job_status_time (status,created_at)
);

CREATE TABLE IF NOT EXISTS privacy_compute_participant (
    job_id VARCHAR(64) NOT NULL,
    party_id VARCHAR(16) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (job_id,party_id)
);

CREATE TABLE IF NOT EXISTS privacy_compute_input_snapshot (
    snapshot_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL,
    party_id VARCHAR(16) NOT NULL,
    dataset_id BIGINT NOT NULL,
    dataset_code VARCHAR(128) NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    digest_algorithm VARCHAR(32) NOT NULL,
    digest_value CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    schema_json LONGTEXT NOT NULL,
    schema_digest CHAR(64) NOT NULL,
    fields_json TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_privacy_input_party (job_id,party_id),
    INDEX idx_privacy_input_dataset (dataset_id,dataset_version)
);

CREATE TABLE IF NOT EXISTS privacy_compute_attempt (
    attempt_id VARCHAR(64) PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    random_context_id VARCHAR(64) NOT NULL,
    external_job_id VARCHAR(160) NULL,
    failure_code VARCHAR(128) NULL,
    failure_reason VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    queued_at DATETIME(3) NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_privacy_attempt_no (job_id,attempt_no)
);

CREATE TABLE IF NOT EXISTS privacy_compute_approval (
    job_id VARCHAR(64) NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(16) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    reason VARCHAR(512) NULL,
    decision_signature CHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    decided_at DATETIME(3) NULL,
    PRIMARY KEY (attempt_id,participant_id),
    INDEX idx_privacy_approval_job (job_id,attempt_id)
);

CREATE TABLE IF NOT EXISTS privacy_compute_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(16) NULL,
    phase VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    message_code VARCHAR(128) NOT NULL,
    payload_bytes BIGINT NULL,
    message_digest CHAR(64) NULL,
    detail VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_privacy_event_job (job_id,event_id),
    INDEX idx_privacy_event_attempt (attempt_id,event_id)
);

CREATE TABLE IF NOT EXISTS privacy_compute_result (
    job_id VARCHAR(64) NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    result_reference VARCHAR(1024) NOT NULL,
    result_digest CHAR(64) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (job_id,attempt_id)
);

CREATE TABLE IF NOT EXISTS privacy_compute_evidence (
    job_id VARCHAR(64) NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    evidence_reference VARCHAR(1024) NULL,
    evidence_json LONGTEXT NOT NULL COMMENT 'sanitized runtime metadata only; no raw input, shares, keys or tokens',
    evidence_digest CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (job_id,attempt_id)
);

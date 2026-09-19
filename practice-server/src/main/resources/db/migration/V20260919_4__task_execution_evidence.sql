-- Explicit execution modes and immutable evidence for TC-4.2 P1/P4 runs.
-- Legacy T1/T2/rating columns retain their original meaning.
SET @db = DATABASE();

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='task_management' AND column_name='execution_mode'),
  'SELECT 1', 'ALTER TABLE task_management ADD COLUMN execution_mode VARCHAR(32) NULL AFTER resource_overrides_json'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='task_management' AND column_name='acceptance_run_id'),
  'SELECT 1', 'ALTER TABLE task_management ADD COLUMN acceptance_run_id VARCHAR(128) NULL AFTER execution_mode'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='task_management' AND column_name='run_round'),
  'SELECT 1', 'ALTER TABLE task_management ADD COLUMN run_round INT NULL AFTER acceptance_run_id'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='task_management' AND column_name='data_preparation_ms'),
  'SELECT 1', 'ALTER TABLE task_management ADD COLUMN data_preparation_ms BIGINT NULL AFTER schedule'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='task_management' AND column_name='compute_duration_ms'),
  'SELECT 1', 'ALTER TABLE task_management ADD COLUMN compute_duration_ms BIGINT NULL AFTER data_preparation_ms'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='task_management' AND column_name='execution_evidence_complete'),
  'SELECT 1', 'ALTER TABLE task_management ADD COLUMN execution_evidence_complete TINYINT(1) NOT NULL DEFAULT 0 AFTER compute_duration_ms'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='task_management' AND column_name='started_at'),
  'SELECT 1', 'ALTER TABLE task_management ADD COLUMN started_at DATETIME(3) NULL AFTER execution_evidence_complete'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='task_management' AND column_name='finished_at'),
  'SELECT 1', 'ALTER TABLE task_management ADD COLUMN finished_at DATETIME(3) NULL AFTER started_at'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS task_execution_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id INT NOT NULL,
    dataset_id BIGINT NULL,
    execution_mode VARCHAR(32) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    job_name VARCHAR(128) NULL,
    pod_name VARCHAR(253) NULL,
    node_name VARCHAR(255) NULL,
    input_path VARCHAR(1024) NULL,
    bytes_processed BIGINT NULL,
    checksum_sha256 CHAR(64) NULL,
    duration_ms BIGINT NULL,
    output_summary LONGTEXT NULL,
    output_checksum_sha256 CHAR(64) NULL,
    attempt INT NULL,
    details_json LONGTEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_task_execution_event_task_time (task_id, occurred_at),
    INDEX idx_task_execution_event_run_type (execution_mode, event_type)
);

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.statistics
  WHERE table_schema=@db AND table_name='task_management' AND index_name='idx_task_acceptance_run'),
  'SELECT 1', 'CREATE INDEX idx_task_acceptance_run ON task_management (acceptance_run_id, run_round, execution_mode)'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

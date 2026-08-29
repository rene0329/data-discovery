-- Registration API hardening: idempotency, candidate reconciliation, replica uniqueness,
-- and normalization of registration-domain timestamps to UTC.

CREATE TABLE IF NOT EXISTS api_idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    action VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_id VARCHAR(64) NULL,
    response_json MEDIUMTEXT NULL,
    execution_status VARCHAR(20) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_api_idempotency_action (resource_type, action, idempotency_key),
    INDEX idx_api_idempotency_created (created_at)
);

CREATE TABLE IF NOT EXISTS registration_schema_migration (
    migration_id VARCHAR(128) PRIMARY KEY,
    applied_at DATETIME(3) NOT NULL
);

-- This repository currently applies SQL migrations operationally instead of through Flyway.
-- Guard the data transformation so rerunning the file cannot shift timestamps twice.
DROP PROCEDURE IF EXISTS apply_registration_api_hardening;
DELIMITER $$
CREATE PROCEDURE apply_registration_api_hardening()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM registration_schema_migration
        WHERE migration_id = 'V20260829__registration_api_hardening'
    ) THEN
        -- Legacy bootstrap created registered datasets and replicas before discovery candidates.
        UPDATE dataset_discovery_candidate c
        JOIN dataset_replica r
          ON r.node_id = c.node_id
         AND r.file_path = c.file_path
        SET c.registered_dataset_id = r.dataset_id
        WHERE c.registered_dataset_id IS NULL;

        -- A physical file on a node can represent only one registered dataset.
        ALTER TABLE dataset_replica
            DROP INDEX uk_dataset_replica_dataset_node_path,
            ADD UNIQUE KEY uk_dataset_replica_node_path (node_id, file_path(384));

        -- Existing registration rows mixed UTC JVM values with UTC+8 MySQL values. Only
        -- database-generated columns are shifted; observed/file timestamps already use UTC.
        UPDATE node_management
        SET verified_at = DATE_SUB(verified_at, INTERVAL 8 HOUR)
        WHERE verified_at IS NOT NULL;

        UPDATE dataset_discovery_candidate
        SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR),
            updated_at = DATE_SUB(updated_at, INTERVAL 8 HOUR);

        UPDATE registered_dataset
        SET verified_at = CASE WHEN verified_at IS NULL THEN NULL ELSE DATE_SUB(verified_at, INTERVAL 8 HOUR) END,
            deleted_at = CASE WHEN deleted_at IS NULL THEN NULL ELSE DATE_SUB(deleted_at, INTERVAL 8 HOUR) END,
            created_at = DATE_SUB(created_at, INTERVAL 8 HOUR),
            updated_at = DATE_SUB(updated_at, INTERVAL 8 HOUR);

        UPDATE dataset_replica
        SET verified_at = CASE WHEN verified_at IS NULL THEN NULL ELSE DATE_SUB(verified_at, INTERVAL 8 HOUR) END,
            created_at = DATE_SUB(created_at, INTERVAL 8 HOUR),
            updated_at = DATE_SUB(updated_at, INTERVAL 8 HOUR);

        UPDATE runtime_image
        SET verified_at = CASE WHEN verified_at IS NULL THEN NULL ELSE DATE_SUB(verified_at, INTERVAL 8 HOUR) END,
            deleted_at = CASE WHEN deleted_at IS NULL THEN NULL ELSE DATE_SUB(deleted_at, INTERVAL 8 HOUR) END,
            created_at = DATE_SUB(created_at, INTERVAL 8 HOUR),
            updated_at = DATE_SUB(updated_at, INTERVAL 8 HOUR);

        UPDATE registration_audit_log
        SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR);

        INSERT INTO registration_schema_migration (migration_id, applied_at)
        VALUES ('V20260829__registration_api_hardening', UTC_TIMESTAMP(3));
    END IF;
END$$
DELIMITER ;

CALL apply_registration_api_hardening();
DROP PROCEDURE apply_registration_api_hardening;

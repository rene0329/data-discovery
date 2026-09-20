-- Bind privacy jobs and approvals to authenticated users and frozen dataset ownership.
-- Depends on V20260920_1. Operational migrations can be restarted after partial DDL.

DROP PROCEDURE IF EXISTS topic4_add_column_if_missing;
DROP PROCEDURE IF EXISTS topic4_add_index_if_missing;

DELIMITER $$
CREATE PROCEDURE topic4_add_column_if_missing(
    IN target_table VARCHAR(64), IN target_column VARCHAR(64), IN column_definition VARCHAR(512))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = target_table AND column_name = target_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', target_table, '` ADD COLUMN ', column_definition);
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE topic4_add_index_if_missing(
    IN target_table VARCHAR(64), IN target_index VARCHAR(64), IN index_definition VARCHAR(512))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = target_table AND index_name = target_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', target_table, '` ADD ', index_definition);
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL topic4_add_column_if_missing('privacy_compute_job', 'initiator_user_id',
    'initiator_user_id BIGINT NULL AFTER initiator');
CALL topic4_add_index_if_missing('privacy_compute_job', 'idx_privacy_job_initiator_user',
    'INDEX idx_privacy_job_initiator_user (initiator_user_id, created_at)');

CALL topic4_add_column_if_missing('privacy_compute_participant', 'slot_id',
    'slot_id VARCHAR(16) NULL AFTER party_id');
CALL topic4_add_column_if_missing('privacy_compute_participant', 'owner_user_id',
    'owner_user_id BIGINT NULL AFTER role');
CALL topic4_add_column_if_missing('privacy_compute_participant', 'owner_username',
    'owner_username VARCHAR(64) NULL AFTER owner_user_id');
CALL topic4_add_column_if_missing('privacy_compute_participant', 'owner_domain_id',
    'owner_domain_id BIGINT NULL AFTER owner_username');
CALL topic4_add_column_if_missing('privacy_compute_participant', 'owner_domain_code',
    'owner_domain_code VARCHAR(64) NULL AFTER owner_domain_id');
CALL topic4_add_index_if_missing('privacy_compute_participant', 'uk_privacy_participant_slot',
    'UNIQUE KEY uk_privacy_participant_slot (job_id, slot_id)');
CALL topic4_add_index_if_missing('privacy_compute_participant', 'idx_privacy_participant_owner',
    'INDEX idx_privacy_participant_owner (owner_user_id, job_id)');
CALL topic4_add_index_if_missing('privacy_compute_participant', 'idx_privacy_participant_domain',
    'INDEX idx_privacy_participant_domain (owner_domain_id, job_id)');

CALL topic4_add_column_if_missing('privacy_compute_input_snapshot', 'slot_id',
    'slot_id VARCHAR(16) NULL AFTER party_id');
CALL topic4_add_column_if_missing('privacy_compute_input_snapshot', 'owner_user_id',
    'owner_user_id BIGINT NULL AFTER slot_id');
CALL topic4_add_column_if_missing('privacy_compute_input_snapshot', 'owner_domain_id',
    'owner_domain_id BIGINT NULL AFTER owner_user_id');

CALL topic4_add_column_if_missing('privacy_compute_approval', 'approver_user_id',
    'approver_user_id BIGINT NULL AFTER participant_id');
CALL topic4_add_column_if_missing('privacy_compute_approval', 'approver_username',
    'approver_username VARCHAR(64) NULL AFTER approver_user_id');
CALL topic4_add_column_if_missing('privacy_compute_approval', 'input_snapshot_digest',
    'input_snapshot_digest CHAR(64) NULL AFTER approver_username');
CALL topic4_add_index_if_missing('privacy_compute_approval', 'idx_privacy_approval_user',
    'INDEX idx_privacy_approval_user (approver_user_id, decision, created_at)');

DROP PROCEDURE topic4_add_column_if_missing;
DROP PROCEDURE topic4_add_index_if_missing;

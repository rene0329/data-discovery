-- Keep administrator intent separate from Kubernetes runtime observations.
-- This file is applied operationally and is safe to run more than once.

CREATE TABLE IF NOT EXISTS registration_schema_migration (
    migration_id VARCHAR(128) PRIMARY KEY,
    applied_at DATETIME(3) NOT NULL
);

DROP PROCEDURE IF EXISTS apply_node_effective_status;
DELIMITER $$
CREATE PROCEDURE apply_node_effective_status()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM registration_schema_migration
        WHERE migration_id = 'V20260829_1__node_effective_status'
    ) THEN
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'node_management'
              AND column_name = 'observed_status'
        ) THEN
            ALTER TABLE node_management
                ADD COLUMN observed_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' AFTER enabled;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'node_management'
              AND column_name = 'observed_status_reason'
        ) THEN
            ALTER TABLE node_management
                ADD COLUMN observed_status_reason VARCHAR(512) NULL AFTER observed_status;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'node_management'
              AND column_name = 'offline_observation_count'
        ) THEN
            ALTER TABLE node_management
                ADD COLUMN offline_observation_count INT NOT NULL DEFAULT 0
                AFTER observed_status_reason;
        END IF;

        UPDATE node_management
        SET observed_status = CASE
                WHEN registration_status = 'OFFLINE' THEN 'OFFLINE'
                WHEN last_seen_at IS NOT NULL THEN 'ONLINE'
                ELSE 'UNKNOWN'
            END,
            observed_status_reason = CASE
                WHEN registration_status = 'OFFLINE' THEN 'Kubernetes node was not observed'
                ELSE NULL
            END,
            registration_status = CASE
                WHEN registration_status = 'OFFLINE' AND enabled = 1 THEN 'ACTIVE'
                WHEN registration_status = 'OFFLINE' THEN 'DISABLED'
                ELSE registration_status
            END;

        INSERT INTO registration_schema_migration (migration_id, applied_at)
        VALUES ('V20260829_1__node_effective_status', UTC_TIMESTAMP(3));
    END IF;
END$$
DELIMITER ;

CALL apply_node_effective_status();
DROP PROCEDURE apply_node_effective_status;

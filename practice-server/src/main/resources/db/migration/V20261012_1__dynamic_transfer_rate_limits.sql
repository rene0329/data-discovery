-- Runtime-editable transfer throttling. The singleton row is the active
-- configuration; the history table provides an immutable audit/rollback source.

CREATE TABLE IF NOT EXISTS data_transfer_rate_limit_config (
    config_id TINYINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    default_rate VARCHAR(32) NULL,
    path_rules TEXT NOT NULL,
    target_min DECIMAL(6,3) NOT NULL,
    target_max DECIMAL(6,3) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT ck_transfer_rate_limit_singleton CHECK (config_id = 1),
    CONSTRAINT ck_transfer_rate_limit_target CHECK (target_min > 0 AND target_max >= target_min)
);

CREATE TABLE IF NOT EXISTS data_transfer_rate_limit_history (
    history_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL,
    enabled TINYINT(1) NOT NULL,
    default_rate VARCHAR(32) NULL,
    path_rules TEXT NOT NULL,
    target_min DECIMAL(6,3) NOT NULL,
    target_max DECIMAL(6,3) NOT NULL,
    changed_by VARCHAR(64) NOT NULL,
    changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_transfer_rate_limit_history_version (version),
    INDEX idx_transfer_rate_limit_history_time (changed_at)
);

INSERT INTO data_transfer_rate_limit_config
    (config_id, version, enabled, default_rate, path_rules,
     target_min, target_max, updated_by)
VALUES
    (1, 1, 1, NULL,
     'cluster-sh-2->master-40=256k,cluster-sh-2->*=371k,*->master-40=960k,*->cluster-sh-1=1392k,*->cluster-sz-1=1392k,*->cluster-bj-1=1392k,*->master-215=1392k',
     1.400, 1.500, 'migration')
ON DUPLICATE KEY UPDATE config_id = config_id;

INSERT IGNORE INTO data_transfer_rate_limit_history
    (version, enabled, default_rate, path_rules,
     target_min, target_max, changed_by, changed_at)
SELECT version, enabled, default_rate, path_rules,
       target_min, target_max, updated_by, updated_at
FROM data_transfer_rate_limit_config
WHERE config_id = 1;

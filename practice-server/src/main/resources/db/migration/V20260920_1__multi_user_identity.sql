-- Multi-user identity, collaboration domains and dataset ownership.
-- This project applies migrations operationally. Every CREATE/ALTER below is guarded so
-- the script can be re-run after a partially completed deployment.

CREATE TABLE IF NOT EXISTS collaboration_domain (
    domain_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_collaboration_domain_code (domain_code)
);

CREATE TABLE IF NOT EXISTS app_role (
    role_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_app_role_code (role_code)
);

CREATE TABLE IF NOT EXISTS app_user (
    user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    domain_id BIGINT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    token_version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_app_user_username (username),
    KEY idx_app_user_domain (domain_id),
    CONSTRAINT fk_app_user_domain FOREIGN KEY (domain_id) REFERENCES collaboration_domain(domain_id)
);

CREATE TABLE IF NOT EXISTS app_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_app_user_role_user FOREIGN KEY (user_id) REFERENCES app_user(user_id),
    CONSTRAINT fk_app_user_role_role FOREIGN KEY (role_id) REFERENCES app_role(role_id)
);

INSERT INTO app_role (role_code, name) VALUES
    ('ADMIN', 'Administrator'),
    ('DATA_OWNER', 'Data owner'),
    ('AUDITOR', 'Auditor')
ON DUPLICATE KEY UPDATE name = VALUES(name);

SET @schema_name = DATABASE();
SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema=@schema_name AND table_name='registered_dataset' AND column_name='owner_user_id'),
    'SELECT 1',
    'ALTER TABLE registered_dataset ADD COLUMN owner_user_id BIGINT NULL AFTER status'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema=@schema_name AND table_name='registered_dataset' AND column_name='owner_domain_id'),
    'SELECT 1',
    'ALTER TABLE registered_dataset ADD COLUMN owner_domain_id BIGINT NULL AFTER owner_user_id'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema=@schema_name AND table_name='registered_dataset' AND index_name='idx_registered_dataset_owner'),
    'SELECT 1',
    'ALTER TABLE registered_dataset ADD INDEX idx_registered_dataset_owner (owner_user_id, owner_domain_id)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

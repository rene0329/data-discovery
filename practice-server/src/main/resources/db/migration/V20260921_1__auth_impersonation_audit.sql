CREATE TABLE IF NOT EXISTS auth_impersonation_audit (
    audit_id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NOT NULL,
    actor_username VARCHAR(64) NOT NULL,
    target_user_id BIGINT NOT NULL,
    target_username VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    client_ip VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (audit_id),
    KEY idx_auth_impersonation_actor_time (actor_user_id, created_at),
    KEY idx_auth_impersonation_target_time (target_user_id, created_at),
    CONSTRAINT fk_auth_impersonation_actor FOREIGN KEY (actor_user_id) REFERENCES app_user(user_id),
    CONSTRAINT fk_auth_impersonation_target FOREIGN KEY (target_user_id) REFERENCES app_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

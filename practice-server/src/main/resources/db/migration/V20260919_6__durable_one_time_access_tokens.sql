-- Durable replay protection for one-time dataset staging tokens.  Only the
-- random token identifier and its scope metadata are stored; the signed token
-- and HMAC secret never enter the database.
CREATE TABLE IF NOT EXISTS dataset_access_token_consumption (
    jti VARCHAR(64) PRIMARY KEY,
    target_node VARCHAR(128) NOT NULL,
    action VARCHAR(32) NOT NULL,
    consumed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    INDEX idx_dataset_access_token_expiry (expires_at)
);

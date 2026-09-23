-- Short-lived, self-service dataset usage grants (访问控制 -> 申请令牌).
-- A grant is active while expires_at > the current UTC time. Expiry is purely
-- time based: nothing updates or deletes a row when it lapses, and the rows
-- double as the history of who asked for what and why.
-- Times are UTC like every other DATETIME(3) column (the JDBC session runs with
-- time_zone='+00:00'; the application writes both timestamps explicitly).
-- Every statement is guarded so the script can be re-run after a partial
-- operational apply, like the other migrations. Depends on V20260920_1
-- (app_user) and V20260919_3 (dataset_access_audit_event).

CREATE TABLE IF NOT EXISTS dataset_access_grant (
    grant_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    dataset_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    -- "active grants for a user": user_id = ? AND expires_at > now
    INDEX idx_dataset_access_grant_user_expiry (user_id, expires_at),
    -- "active grant for user + dataset(s)": user_id = ? AND dataset_id IN (...) AND expires_at > now
    INDEX idx_dataset_access_grant_user_dataset_expiry (user_id, dataset_id, expires_at),
    INDEX idx_dataset_access_grant_dataset (dataset_id),
    CONSTRAINT fk_dataset_access_grant_user FOREIGN KEY (user_id) REFERENCES app_user(user_id),
    CONSTRAINT fk_dataset_access_grant_dataset FOREIGN KEY (dataset_id) REFERENCES registered_dataset(dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The 异常访问日志 page lists the newest DENIED audit events
-- (WHERE decision = ? ORDER BY event_id DESC LIMIT n); without this index that
-- query walks the whole, ever-growing audit table backwards.
SET @db = DATABASE();
SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.statistics
  WHERE table_schema=@db AND table_name='dataset_access_audit_event'
    AND index_name='idx_dataset_access_decision_event'),
  'SELECT 1',
  'ALTER TABLE dataset_access_audit_event ADD INDEX idx_dataset_access_decision_event (decision, event_id)'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

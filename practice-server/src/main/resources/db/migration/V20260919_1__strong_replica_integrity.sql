-- Separate the immutable dataset-version authority from per-replica measurements.
-- Every column is guarded independently so the operational migration can be
-- rerun safely after an interrupted MySQL DDL sequence.
SET @db = DATABASE();

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='dataset_metadata' AND column_name='authoritative_size_bytes'),
  'SELECT 1', 'ALTER TABLE dataset_metadata ADD COLUMN authoritative_size_bytes BIGINT NULL AFTER metadata_version'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='dataset_discovery_candidate' AND column_name='checksum_algorithm'),
  'SELECT 1', 'ALTER TABLE dataset_discovery_candidate ADD COLUMN checksum_algorithm VARCHAR(32) NULL AFTER size_bytes'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- verified_at already exists in V20260827__registration_foundation, but keep
-- this guard for installations created from an older partial schema.
SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='dataset_discovery_candidate' AND column_name='verified_at'),
  'SELECT 1', 'ALTER TABLE dataset_discovery_candidate ADD COLUMN verified_at DATETIME(3) NULL AFTER availability'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='dataset_replica' AND column_name='checksum_algorithm'),
  'SELECT 1', 'ALTER TABLE dataset_replica ADD COLUMN checksum_algorithm VARCHAR(32) NULL AFTER size_bytes'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=@db AND table_name='dataset_replica' AND column_name='verification_message'),
  'SELECT 1', 'ALTER TABLE dataset_replica ADD COLUMN verification_message VARCHAR(512) NULL AFTER availability'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Historical checksums were optional MD5 values and cannot satisfy the new
-- SHA-256 invariant. They remain visible as measurements but are removed from
-- the effective readable index until explicitly verified.
UPDATE dataset_discovery_candidate
SET availability = 'UNVERIFIED', verified_at = NULL
WHERE checksum_algorithm IS NULL OR UPPER(checksum_algorithm) != 'SHA-256'
   OR checksum IS NULL OR checksum NOT REGEXP '^[0-9A-Fa-f]{64}$';

UPDATE dataset_replica
SET availability = 'UNVERIFIED', verified_at = NULL,
    verification_message = 'requires SHA-256 verification'
WHERE checksum_algorithm IS NULL OR UPPER(checksum_algorithm) != 'SHA-256'
   OR checksum IS NULL OR checksum NOT REGEXP '^[0-9A-Fa-f]{64}$';

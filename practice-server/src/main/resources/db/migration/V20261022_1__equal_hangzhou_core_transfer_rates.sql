-- Equal transfer limits for Hangzhou and core replicas. First-match rules
-- override prior differential defaults without changing other source sites.
UPDATE data_transfer_rate_limit_config
SET version = version + 1,
    path_rules = CONCAT('cluster-hz-1->*=1408k,master-40->*=1408k,master-141->*=1408k,master-215->*=1408k,', path_rules),
    updated_by = 'equal-hz-core-migration', updated_at = CURRENT_TIMESTAMP(3)
WHERE config_id = 1 AND updated_by <> 'equal-hz-core-migration';

INSERT IGNORE INTO data_transfer_rate_limit_history
    (version, enabled, adaptive_enabled, bandwidth_probe_enabled,
     default_rate, path_rules, target_min, target_max,
     adaptive_safety_factor, measurement_max_age_seconds,
     adaptive_central_node, changed_by, changed_at)
SELECT version, enabled, adaptive_enabled, bandwidth_probe_enabled,
       default_rate, path_rules, target_min, target_max,
       adaptive_safety_factor, measurement_max_age_seconds,
       adaptive_central_node, updated_by, updated_at
FROM data_transfer_rate_limit_config WHERE config_id = 1;

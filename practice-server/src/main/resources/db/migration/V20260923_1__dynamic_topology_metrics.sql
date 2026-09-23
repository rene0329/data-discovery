-- Link set is now derived from node sites by NetworkTopologyService; this table only caches
-- probe measurements, keyed by node_id so renames never orphan a link.
-- logical_topology_edge is left in place (unused) for history.
CREATE TABLE IF NOT EXISTS node_link_metric (
    source_id INT NOT NULL,
    target_id INT NOT NULL,
    bandwidth BIGINT NULL COMMENT 'Measured Mbps',
    latency DOUBLE NULL COMMENT 'Measured RTT in ms',
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    measurement_time DATETIME(3) NULL COMMENT 'UTC report receipt time',
    PRIMARY KEY (source_id, target_id),
    CHECK (source_id < target_id)
);

INSERT INTO node_link_metric (source_id, target_id, bandwidth, latency, status, measurement_time)
SELECT LEAST(s.node_id, t.node_id), GREATEST(s.node_id, t.node_id),
       e.bandwidth, e.latency, e.status, e.measurement_time
FROM logical_topology_edge e
JOIN node_management s ON s.node_name = e.source_node_name AND s.deleted_at IS NULL
JOIN node_management t ON t.node_name = e.target_node_name AND t.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    bandwidth = VALUES(bandwidth), latency = VALUES(latency),
    status = VALUES(status), measurement_time = VALUES(measurement_time);

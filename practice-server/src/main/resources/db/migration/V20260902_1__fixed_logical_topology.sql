-- Approved logical links. Probe reports may update metrics, never insert links.
-- Names keep the policy independent of environment-specific node_id values.
-- Existing edge_management measurements are retained as historical observations.
CREATE TABLE logical_topology_edge (
    edge_id INT AUTO_INCREMENT PRIMARY KEY,
    source_node_name VARCHAR(128) NOT NULL,
    target_node_name VARCHAR(128) NOT NULL,
    bandwidth BIGINT NULL COMMENT 'Measured Mbps',
    latency DOUBLE NULL COMMENT 'Measured RTT in ms',
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    measurement_time DATETIME(3) NULL COMMENT 'UTC report receipt time',
    UNIQUE KEY uk_logical_topology_pair (source_node_name, target_node_name),
    CHECK (source_node_name < target_node_name)
);

INSERT INTO logical_topology_edge (source_node_name, target_node_name) VALUES
    ('master-88', 'master-89'),
    ('master-88', 'master-90'),
    ('master-89', 'master-90'),
    ('alihz', 'master-88'),
    ('alish', 'master-88'),
    ('alibj', 'master-90');

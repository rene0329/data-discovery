package org.example.mapper;

import org.apache.ibatis.session.Configuration;
import org.example.entity.NodeManagement;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class NodeSchedulingMapperTest {
    @Test
    void topologyQueryLoadsEveryFieldUsedToEvaluateNodeAvailability() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(NodeManagementMapper.class);
        String statement = NodeManagementMapper.class.getName() + ".selectAllNodes";
        String sql = configuration.getMappedStatement(statement).getBoundSql(null)
                .getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String projection = sql.substring(sql.indexOf("select ") + 7, sql.indexOf(" from "));

        // Service tests construct complete NodeManagement objects; guard the real SQL too.
        // Missing enabled becomes null and excludes every node from the topology.
        for (String field : new String[]{"node_id", "enabled", "registration_status",
                "observed_status", "observed_status_reason", "last_seen_at", "deleted_at"}) {
            assertTrue("*".equals(projection) || projection.contains(field),
                    "Topology node query must load " + field);
        }
        assertEquals(NodeManagement.class,
                configuration.getMappedStatement(statement).getResultMaps().get(0).getType());
        assertTrue(sql.contains("where deleted_at is null"));
        // Keep all non-deleted nodes; availability is evaluated by the service, not bypassed.
        assertFalse(sql.contains("enabled ="));
        assertFalse(sql.contains("registration_status ="));
    }
}

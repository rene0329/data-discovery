package org.example.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.example.entity.EdgeManagement;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LogicalTopologyMapperTest {
    @Test
    void metricQueriesAreKeyedByNodeIdAndUpsertKeepsValuesOnFailure() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/EdgeManagementMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String namespace = EdgeManagementMapper.class.getName() + ".";
        assertEquals(EdgeManagement.class, configuration.getMappedStatement(namespace + "selectAllMetrics")
                .getResultMaps().get(0).getType());
        assertTrue(configuration.getMappedStatement(namespace + "selectAllMetrics").getResultMaps().get(0)
                .getResultMappings().stream().anyMatch(mapping -> "measurementTime".equals(mapping.getProperty())));
        Map<String, Object> pair = new HashMap<>();
        pair.put("sourceId", 14); pair.put("targetId", 7);
        BoundSql find = configuration.getMappedStatement(namespace + "findBySourceAndTargetNode").getBoundSql(pair);
        assertEquals(4, find.getParameterMappings().size());
        assertFalse(find.getSql().contains("node_name"));
        String upsert = configuration.getMappedStatement(namespace + "upsertMetric")
                .getBoundSql(EdgeManagement.builder().sourceId(7).targetId(14).build()).getSql();
        assertTrue(upsert.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(upsert.contains("COALESCE(VALUES(bandwidth), bandwidth)"));
        assertTrue(configuration.hasStatement(namespace + "deactivateByNodeId"));
    }
}

package org.example.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class NodePublicIpMapperTest {
    @Test
    void preservesDiscoveredIpOnKubernetesNullObservationsAndScopesIpWrites() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/NodeManagementMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String namespace = NodeManagementMapper.class.getName() + ".";
        for (String statement : new String[]{"updateNodeObservation", "updateNodeFromK8s"}) {
            String sql = configuration.getMappedStatement(namespace + statement)
                    .getBoundSql(Collections.emptyMap()).getSql();
            assertTrue(sql.contains("external_ip = COALESCE(NULLIF(?, ''), external_ip)"));
        }
        String sql = configuration.getMappedStatement(namespace + "updateObservedPublicIp")
                .getBoundSql(Collections.emptyMap()).getSql();
        assertTrue(sql.contains("cluster = ? AND k8s_uid = ? AND node_name = ?"));
        assertTrue(sql.contains("deleted_at IS NULL"));
        assertFalse(sql.contains("enabled ="));
        assertFalse(sql.contains("registration_status ="));
    }
}

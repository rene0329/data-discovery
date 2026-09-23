package org.example.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DatasetReplicaRemovalMapperTest {
    private Configuration configuration;
    private Map<String, Object> params;

    @BeforeEach
    void setup() throws Exception {
        configuration = new Configuration();
        String resource = "mapper/DatasetRegistrationMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        params = new HashMap<>();
        params.put("replicaId", 100L);
        params.put("datasetId", 42L);
        params.put("nodeId", 3);
        params.put("filePath", "/dataset/sales.npz");
    }

    @Test
    void replicaDeletionIsScopedToTheOwningDataset() {
        assertEquals(SqlCommandType.DELETE, statement("deleteReplica"));
        assertEquals("DELETE FROM dataset_replica WHERE replica_id = ? AND dataset_id = ?",
                sql("deleteReplica"));
    }

    @Test
    void candidateDeletionMatchesTheExactNodeAndPath() {
        assertEquals(SqlCommandType.DELETE, statement("deleteCandidateByNodePath"));
        assertEquals("DELETE FROM dataset_discovery_candidate WHERE node_id = ? AND file_path = ?",
                sql("deleteCandidateByNodePath"));
    }

    private SqlCommandType statement(String id) {
        return configuration.getMappedStatement("org.example.mapper.DatasetRegistrationMapper." + id)
                .getSqlCommandType();
    }

    private String sql(String id) {
        return configuration.getMappedStatement("org.example.mapper.DatasetRegistrationMapper." + id)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}

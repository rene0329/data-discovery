package org.example.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.example.entity.RegisteredDataset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The retired per-dataset holder is neither written nor joined by the catalog statements. */
class DatasetRegistrationMapperSqlTest {
    private Configuration config;

    @BeforeEach
    void setUp() throws Exception {
        config = new Configuration();
        String resource = "mapper/DatasetRegistrationMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
    }

    @Test
    void registrationLeavesTheHolderColumnsNull() {
        String insert = sql("insertDataset", new RegisteredDataset());
        assertFalse(insert.contains("owner_user_id"), insert);
        assertFalse(insert.contains("owner_domain_id"), insert);
        assertTrue(insert.startsWith("INSERT INTO registered_dataset ("), insert);
    }

    @Test
    void datasetReadsNoLongerJoinUsersOrHolderDomains() {
        Map<String, Object> params = new HashMap<>();
        params.put("datasetId", 1L);
        params.put("datasetCode", "sales");
        params.put("datasetVersion", "1.0");
        params.put("query", "sa");
        params.put("status", "ACTIVE");
        for (String statement : new String[]{"findDatasetById", "findDatasetByCodeAndVersion", "listDatasets"}) {
            String select = sql(statement, params);
            assertTrue(select.startsWith("SELECT rd.* FROM registered_dataset rd WHERE"), select);
            assertFalse(select.contains("app_user"), select);
            assertFalse(select.contains("owner_"), select);
            assertFalse(select.contains("JOIN"), select);
        }
    }

    private String sql(String statement, Object params) {
        return config.getMappedStatement("org.example.mapper.DatasetRegistrationMapper." + statement)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}

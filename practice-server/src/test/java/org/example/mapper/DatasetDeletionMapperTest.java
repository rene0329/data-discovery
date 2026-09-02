package org.example.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetDeletionMapperTest {
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
        params.put("datasetId", 42L);
        params.put("datasetName", "dataset");
    }

    @Test
    void tasksUseExactJsonIdsAndOnlyFallbackToLegacyNames() {
        String sql = sql("countTaskReferences");
        assertTrue(sql.contains("CASE WHEN JSON_VALID(dataset_ids_json)"));
        assertTrue(sql.contains("JSON_CONTAINS(dataset_ids_json, CAST(? AS JSON))"));
        assertTrue(sql.contains("ELSE LOCATE(?, selected_data) > 0"));
    }

    @Test
    void migrationGuardsIncludeLegacyIdsOnlyWhenPresent() {
        String sql = sql("countActiveMigrationReferences");
        assertTrue(sql.contains("registered_dataset_id = ?"));
        assertTrue(sql.contains("status NOT IN ('COMPLETED', 'FAILED')"));
        assertFalse(sql.contains("AND data_id = ?"));
        params.put("legacyDataId", 7);
        assertTrue(sql("countActiveMigrationReferences")
                .contains("registered_dataset_id IS NULL AND data_id = ?"));
    }

    @Test
    void schedulingChecksThePlanAndDeletionKeepsHistoricalRows() {
        String scheduling = sql("countActiveSchedulingReferences");
        assertTrue(scheduling.contains("a.dataset_id = ?"));
        assertTrue(scheduling.contains("p.status IN ('ACCEPTED', 'RUNNING')"));
        String deletion = sql("softDeleteDataset");
        assertTrue(deletion.contains("UPDATE registered_dataset"));
        assertTrue(deletion.contains("status = 'DELETED'"));
        assertTrue(deletion.contains("deleted_at = UTC_TIMESTAMP(3)"));
        assertFalse(deletion.contains("DELETE FROM"));
    }

    private String sql(String statement) {
        return configuration.getMappedStatement("org.example.mapper.DatasetRegistrationMapper." + statement)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ");
    }
}

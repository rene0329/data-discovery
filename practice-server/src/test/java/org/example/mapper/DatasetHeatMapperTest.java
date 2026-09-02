package org.example.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class DatasetHeatMapperTest {
    @Test
    void heatSqlUsesLogicalIdsElapsedTimeAndPreservesCatalogOrdering() throws Exception {
        Configuration config = new Configuration();
        String resource = "mapper/DatasetRegistrationMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        for (String name : new String[]{"refreshHeat", "recordHeatAccess"}) {
            String sql = config.getMappedStatement("org.example.mapper.DatasetRegistrationMapper." + name)
                    .getBoundSql(Collections.emptyMap()).getSql().replaceAll("\\s+", " ");
            assertTrue(sql.contains("UPDATE registered_dataset"));
            assertTrue(sql.contains("TIMESTAMPDIFF(MICROSECOND"));
            assertTrue(sql.contains("updated_at = updated_at"));
            assertTrue(sql.contains("deleted_at IS NULL"));
            assertFalse(sql.contains("data_name"));
            if (name.equals("recordHeatAccess")) assertTrue(sql.contains("dataset_id = ?"));
        }
        String insert = config.getMappedStatement("org.example.mapper.DatasetRegistrationMapper.insertDataset")
                .getBoundSql(new org.example.entity.RegisteredDataset()).getSql();
        assertTrue(insert.contains("heat_updated_at"));
        assertTrue(insert.contains("UTC_TIMESTAMP(3)"));
    }
}

package org.example.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulingPlanMapperTest {
    @Test
    void failedFilterIncludesPartialExecutionInBothRowsAndCount() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/SchedulingPlanMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("query", "external-plan");
        params.put("offset", 0L);
        params.put("limit", 10);
        for (String statement : new String[]{"listPlans", "countPlans"}) {
            params.put("status", "FAILED");
            String failedSql = sql(configuration, statement, params);
            assertTrue(failedSql.contains("status IN ('FAILED', 'PARTIAL_COMPLETED')"));
            assertTrue(failedSql.contains("LOCATE(?, external_plan_id)"));

            params.put("status", "COMPLETED");
            String completedSql = sql(configuration, statement, params);
            assertTrue(completedSql.contains("status = ?"));
            assertFalse(completedSql.contains("PARTIAL_COMPLETED"));

            params.put("status", "");
            assertFalse(sql(configuration, statement, params).contains("AND status"));
        }
    }

    private String sql(Configuration configuration, String statement, Map<String, Object> params) {
        BoundSql boundSql = configuration.getMappedStatement(
                "org.example.mapper.SchedulingPlanMapper." + statement).getBoundSql(params);
        return boundSql.getSql().replaceAll("\\s+", " ");
    }
}

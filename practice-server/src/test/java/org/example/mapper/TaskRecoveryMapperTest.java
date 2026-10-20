package org.example.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskRecoveryMapperTest {
    @Test
    void recoveryPreservesCompletedRecordsAndOnlyChangesLinkedReservations() throws Exception {
        Configuration config = new Configuration();
        String resource = "mapper/TaskRecoveryMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("taskId", 243);
        params.put("reason", "restart");
        for (String statement : new String[]{"listInterruptedTasks", "failTask", "failMigrations", "failAssignments", "failPlans"}) {
            String sql = config.getMappedStatement("org.example.mapper.TaskRecoveryMapper." + statement)
                    .getBoundSql(params).getSql().replaceAll("\\s+", " ");
            if (statement.equals("listInterruptedTasks") || statement.equals("failTask")) {
                assertTrue(sql.contains("status NOT IN"));
                for (String terminal : new String[]{"已完成", "执行失败", "部分完成", "COMPLETED", "FAILED", "PARTIAL_COMPLETED"}) {
                    assertTrue(sql.contains("'" + terminal + "'"));
                }
            } else if (statement.equals("failMigrations")) {
                assertTrue(sql.contains("task_id = ?"));
                assertTrue(sql.contains("status NOT IN ('COMPLETED', 'FAILED')"));
            } else {
                assertTrue(sql.contains("internal_task_id = ?"));
                assertTrue(sql.contains("status IN ('ACCEPTED', 'RUNNING')"));
            }
        }
    }
}

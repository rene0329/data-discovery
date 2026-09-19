package org.example.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.example.entity.TaskManagement;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagementMapperTest {
    @Test
    void analysisExcludesHistoricalTasksWithoutAComparisonPath() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/TaskManagementMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        Map<String, Object> params = new HashMap<>();
        params.put("query", "任务");
        BoundSql boundSql = configuration.getMappedStatement(
                "org.example.mapper.TaskManagementMapper.listWithAnalysis").getBoundSql(params);
        String sql = boundSql.getSql().replaceAll("\\s+", " ");

        assertTrue(sql.contains("LOCATE('中心化调度方案:', tm.schedule) = 0"));
        assertTrue(sql.contains("TRIM(SUBSTRING_INDEX(tm.schedule, '中心化调度方案:', -1)) != ''"));
        assertTrue(sql.contains("tm.task_name LIKE CONCAT('%', ?, '%')"));
    }

    @Test
    void registeredTaskPersistenceUsesSemanticEvidenceFields() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/TaskManagementMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        TaskManagement task = TaskManagement.builder().taskId(3).status("已完成")
                .dataPreparationMs(1200L).computeDurationMs(3400L)
                .executionEvidenceComplete(true).build();
        String insertSql = configuration.getMappedStatement(
                        "org.example.mapper.TaskManagementMapper.submitData")
                .getBoundSql(task).getSql().replaceAll("\\s+", " ");
        String updateSql = configuration.getMappedStatement(
                        "org.example.mapper.TaskManagementMapper.updateExecutionSummary")
                .getBoundSql(task).getSql().replaceAll("\\s+", " ");

        assertTrue(insertSql.contains("execution_mode, acceptance_run_id, run_round"));
        assertTrue(updateSql.contains("data_preparation_ms = ?"));
        assertTrue(updateSql.contains("compute_duration_ms = ?"));
        assertTrue(updateSql.contains("execution_evidence_complete = ?"));
        assertFalse(updateSql.matches("(?i).*\\bT1\\s*=.*"));
        assertFalse(updateSql.matches("(?i).*\\bT2\\s*=.*"));
        assertFalse(updateSql.matches("(?i).*\\brating\\s*=.*"));
    }
}

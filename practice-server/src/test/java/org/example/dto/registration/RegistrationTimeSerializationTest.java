package org.example.dto.registration;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.service.NodeAvailability;
import org.example.json.JacksonObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationTimeSerializationTest {
    @Test
    void registrationTimesUseRfc3339UtcShape() throws Exception {
        LocalDateTime time = LocalDateTime.of(2026, 8, 29, 8, 15, 30);
        NodeManagement node = NodeManagement.builder()
                .nodeId(2).nodeName("compute-1").lastSeenAt(time).verifiedAt(time).build();
        RegisteredNodeView nodeView = RegisteredNodeView.from(node, Collections.emptyMap(),
                new NodeAvailability("AVAILABLE", true, null));
        DatasetReplica replica = DatasetReplica.builder().replicaId(1L).lastSeenAt(time).build();

        JacksonObjectMapper mapper = new JacksonObjectMapper();
        String nodeJson = mapper.writeValueAsString(nodeView);
        String replicaJson = mapper.writeValueAsString(replica);

        assertTrue(nodeJson.contains("2026-08-29T08:15:30Z"));
        assertTrue(replicaJson.contains("2026-08-29T08:15:30Z"));
    }
}

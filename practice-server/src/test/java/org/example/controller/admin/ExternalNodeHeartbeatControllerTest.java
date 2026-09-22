package org.example.controller.admin;

import org.example.dto.ExternalNodeHeartbeatDto;
import org.example.mapper.NodeManagementMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExternalNodeHeartbeatControllerTest {
    private final NodeManagementMapper mapper = mock(NodeManagementMapper.class);
    private final ExternalNodeHeartbeatController controller =
            new ExternalNodeHeartbeatController(mapper, 3);

    private ExternalNodeHeartbeatDto heartbeat(boolean online) {
        ExternalNodeHeartbeatDto report = new ExternalNodeHeartbeatDto();
        report.setClusterId("zj-external-aliyun");
        report.setNodeName("alihz");
        report.setInternalIp("10.214.0.1");
        report.setOnline(online);
        return report;
    }

    @Test
    void refreshesOnlineExternalNode() {
        when(mapper.markExternalNodeOnline("zj-external-aliyun", "alihz", "10.214.0.1"))
                .thenReturn(1);
        assertEquals(200, controller.report(heartbeat(true)).getStatusCodeValue());
        verify(mapper).markExternalNodeOnline("zj-external-aliyun", "alihz", "10.214.0.1");
    }

    @Test
    void countsFailedHeartbeatsBeforeMarkingOffline() {
        ExternalNodeHeartbeatDto report = heartbeat(false);
        report.setReason("tunnel unavailable");
        when(mapper.markExternalNodeUnreachable("zj-external-aliyun", "alihz", "10.214.0.1",
                "tunnel unavailable", 3)).thenReturn(1);
        assertEquals(200, controller.report(report).getStatusCodeValue());
        verify(mapper).markExternalNodeUnreachable("zj-external-aliyun", "alihz", "10.214.0.1",
                "tunnel unavailable", 3);
    }

    @Test
    void rejectsIncompleteHeartbeat() {
        ExternalNodeHeartbeatDto report = heartbeat(true);
        report.setInternalIp(null);
        assertEquals(400, controller.report(report).getStatusCodeValue());
        verifyNoInteractions(mapper);
    }
}

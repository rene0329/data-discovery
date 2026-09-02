package org.example.controller.admin;

import org.example.dto.NodePublicIpDto;
import org.example.mapper.NodeManagementMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodePublicIpControllerTest {
    private final NodeManagementMapper mapper = mock(NodeManagementMapper.class);
    private final NodePublicIpController controller = new NodePublicIpController(mapper);

    private NodePublicIpDto report(String ip) {
        NodePublicIpDto report = new NodePublicIpDto();
        report.setClusterId("cluster-a"); report.setK8sUid("uid-a");
        report.setNodeName("node-a"); report.setPublicIp(ip);
        return report;
    }

    @Test
    void updatesOnlyTheExactRegisteredIdentity() {
        when(mapper.updateObservedPublicIp("cluster-a", "uid-a", "node-a", "8.8.8.8")).thenReturn(1);
        assertEquals(200, controller.report(report("8.8.8.8\n")).getStatusCodeValue());
        verify(mapper).updateObservedPublicIp("cluster-a", "uid-a", "node-a", "8.8.8.8");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void rejectsInvalidValuesAndMissingIdentityBeforeWriting() {
        assertEquals(400, controller.report(report("10.213.0.1")).getStatusCodeValue());
        assertEquals(400, controller.report(report("<html>error</html>")).getStatusCodeValue());
        NodePublicIpDto missingUid = report("8.8.8.8");
        missingUid.setK8sUid(null);
        assertEquals(400, controller.report(missingUid).getStatusCodeValue());
        assertEquals(400, controller.report(null).getStatusCodeValue());
        verifyNoInteractions(mapper);
    }

    @Test
    void doesNotRegisterAnUnknownNode() {
        assertEquals(404, controller.report(report("8.8.8.8")).getStatusCodeValue());
        verify(mapper).updateObservedPublicIp("cluster-a", "uid-a", "node-a", "8.8.8.8");
        verifyNoMoreInteractions(mapper);
    }
}

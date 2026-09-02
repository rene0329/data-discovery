package org.example.controller.admin;

import org.example.handler.CommonExceptionHandler;
import org.example.mapper.*;
import org.example.service.K8sTaskOrchestratorService;
import org.example.service.NodeAvailabilityService;
import org.example.service.NetworkTopologyService;
import org.example.service.PublicIpLocationService;
import org.example.entity.DataManagement;
import org.example.vo.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CommonContractTest {
    private MockMvc mvc;
    private DataManagementMapper datasets;
    private NodeManagementMapper nodes;

    @BeforeEach
    void setup() {
        datasets = mock(DataManagementMapper.class);
        nodes = mock(NodeManagementMapper.class);
        when(datasets.adminList(anyString())).thenReturn(Collections.emptyList());
        mvc = MockMvcBuilders.standaloneSetup(new CommonController(datasets, nodes,
                mock(TaskManagementMapper.class), mock(MigrationTaskMapper.class),
                mock(NetworkTopologyService.class), mock(K8sTaskOrchestratorService.class),
                mock(RestTemplate.class), new NodeAvailabilityService(300), mock(PublicIpLocationService.class)))
                .setControllerAdvice(new CommonExceptionHandler()).build();
    }

    @Test
    void invalidPagesAreClientErrorsNot500() throws Exception {
        for (String query : new String[]{"pageSize=10000", "page=0", "pageSize=abc"}) {
            mvc.perform(get("/common/dataManagement?" + query))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
    }

    @Test
    void paginationKeepsTailAndHandlesIntegerOverflow() {
        java.util.List<Integer> items = IntStream.range(0, 205).boxed().collect(Collectors.toList());
        assertEquals(5, PageResult.of(items, 3, 100).getList().size());
        assertEquals(205, PageResult.of(items, 3, 100).getTotal());
        assertTrue(PageResult.of(items, Integer.MAX_VALUE, 100).getList().isEmpty());
    }

    @Test
    void unsupportedEditsDoNotWrite() throws Exception {
        mvc.perform(post("/common/updateDataItem").contentType("application/json")
                .content("{\"dataId\":1,\"dataHeat\":8,\"dataSize\":123}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/common/updateNodeSettings").contentType("application/json")
                .content("{\"nodeId\":1,\"nodeName\":\"changed\"}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value(410));
        verifyNoInteractions(datasets, nodes);
    }

    @Test
    void heatEditUsesPhysicalIdAndReportsMissingRows() throws Exception {
        when(datasets.updateHeatById(1, 8.0)).thenReturn(1);
        when(datasets.findById(1)).thenReturn(DataManagement.builder().dataId(1).dataHeat(8.0).build());
        mvc.perform(post("/common/updateDataItem").contentType("application/json")
                .content("{\"dataId\":1,\"dataHeat\":8}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dataId").value(1))
                .andExpect(jsonPath("$.data.dataHeat").value(8.0));
        mvc.perform(post("/common/updateDataItem").contentType("application/json")
                .content("{\"dataId\":999,\"dataHeat\":8}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }
}

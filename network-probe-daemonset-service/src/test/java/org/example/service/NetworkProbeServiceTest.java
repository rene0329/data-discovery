package org.example.service;

import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NetworkProbeServiceTest {
    @Test
    void readsAverageFromActualClusterPingOutput() {
        String output = "PING 10.212.14.89 (10.212.14.89) 56(84) bytes of data.\n"
                + "64 bytes from 10.212.14.89: icmp_seq=1 ttl=64 time=0.301 ms\n"
                + "64 bytes from 10.212.14.89: icmp_seq=2 ttl=64 time=0.213 ms\n"
                + "2 packets transmitted, 2 received, 0% packet loss, time 1001ms\n"
                + "rtt min/avg/max/mdev = 0.213/0.257/0.301/0.044 ms\n";
        assertEquals(0.257, NetworkProbeService.parseLatency(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "round-trip min/avg/max = 0.100/0.257/0.500 ms",
            "round-trip min/avg/max/stddev = 0.100/0.257/0.500/0.044 ms\r\n",
            "rtt min/avg/max/mdev = 0.100/0.257/0.500/0.044 ms"
    })
    void supportsPingSummaryFormats(String output) {
        assertEquals(0.257, NetworkProbeService.parseLatency(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "4 packets transmitted, 0 received, 100% packet loss",
            "ping: unknown host", "rtt min/avg/max/mdev = 1/NaN/2/0 ms",
            "rtt min/avg/max/mdev = 1/Infinity/2/0 ms",
            "rtt min/avg/max/mdev = 1/-1/2/0 ms",
            "rtt min/avg/max/mdev = invalid"})
    void missingOrInvalidLatencyStaysFailed(String output) {
        assertEquals(-1, NetworkProbeService.parseLatency(output));
    }

    @Test
    void zeroLatencyIsAValidMeasurement() {
        assertEquals(0, NetworkProbeService.parseLatency("rtt min/avg/max/mdev = 0/0/0/0 ms"));
    }

    @Test
    void readsReceivedAggregateRegardlessOfFieldOrderAndScientificNotation() {
        // sum_sent deliberately follows sum_received; the last bps field is not the receiver rate.
        String output = "{\"end\":{\"sum_received\":{\"bits_per_second\":6.023201145840092e9},"
                + "\"sum_sent\":{\"bits_per_second\":6053929585.7497673}}}";
        assertEquals(6023201145L, NetworkProbeService.parseBandwidth(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void readsReceiverThroughputForBothIperfDirections(String sender) {
        assertEquals(55000000L, NetworkProbeService.parseBandwidth(
                "{\"end\":{\"sum_received\":{\"sender\":" + sender + ",\"bits_per_second\":5.5e7}}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "not json", "{}",
            "{\"error\":\"the server is busy running a test\"}",
            "{\"end\":{\"sum_sent\":{\"bits_per_second\":1000000}}}",
            "{\"end\":{\"sum_received\":{\"bits_per_second\":0}}}",
            "{\"end\":{\"sum_received\":{\"bits_per_second\":-1}}}",
            "{\"end\":{\"sum_received\":{\"bits_per_second\":\"1000\"}}}",
            "{\"error\":\"test interrupted\",\"end\":{\"sum_received\":{\"bits_per_second\":1000}}}"})
    void failedOrIncompleteIperfResultsStayFailed(String output) {
        assertEquals(-1, NetworkProbeService.parseBandwidth(output));
    }

    @Test
    void busyIperfServerRetriesThenUsesTheRealMeasurement() throws Exception {
        NetworkProbeService probe = spy(new NetworkProbeService());
        doNothing().when(probe).waitForIperfRetry(anyInt());
        String busy = "{\"error\":\"the server is busy running a test. try again later\"}";
        doReturn(busy, busy, "{\"end\":{\"sum_received\":{\"bits_per_second\":92896362.19}}}")
                .when(probe).runIperfCommand("10.212.14.88", false);
        assertEquals(92896362L, probe.runIperf("10.212.14.88", false));
        verify(probe, times(3)).runIperfCommand("10.212.14.88", false);
        verify(probe).waitForIperfRetry(1);
        verify(probe).waitForIperfRetry(2);
    }

    @Test
    void persistentContentionIsBoundedAndStillFails() throws Exception {
        NetworkProbeService probe = spy(new NetworkProbeService());
        doNothing().when(probe).waitForIperfRetry(anyInt());
        doReturn("{\"error\":\"the server is busy running a test. try again later\"}")
                .when(probe).runIperfCommand("10.212.14.88", true);
        assertEquals(-1, probe.runIperf("10.212.14.88", true));
        verify(probe, times(4)).runIperfCommand("10.212.14.88", true);
        verify(probe, times(3)).waitForIperfRetry(anyInt());
    }

    @Test
    void connectionFailuresDoNotEnterTheContentionRetryLoop() throws Exception {
        NetworkProbeService probe = spy(new NetworkProbeService());
        doReturn("{\"error\":\"unable to connect to server: Connection refused\"}")
                .when(probe).runIperfCommand("10.212.14.88", false);
        assertEquals(-1, probe.runIperf("10.212.14.88", false));
        verify(probe).runIperfCommand("10.212.14.88", false);
        verify(probe, never()).waitForIperfRetry(anyInt());
    }

    @Test
    void reportsParsedMetricsAndStillReportsFailedProbes() {
        KubernetesClient k8s = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        when(k8s.nodes().list()).thenReturn(new NodeListBuilder().withItems(
                new NodeBuilder().withNewMetadata().withName("master-88").endMetadata().build(),
                new NodeBuilder().withNewMetadata().withName("master-89").endMetadata()
                        .withNewStatus().addNewAddress().withType("InternalIP").withAddress("10.212.14.89")
                        .endAddress().endStatus().build()).build());
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        NetworkProbeService probe = spy(new NetworkProbeService());
        ReflectionTestUtils.setField(probe, "k8sClient", k8s);
        ReflectionTestUtils.setField(probe, "restTemplate", http);
        ReflectionTestUtils.setField(probe, "localNodeName", "master-88");
        ReflectionTestUtils.setField(probe, "probeAllNodes", true);
        ReflectionTestUtils.setField(probe, "centralMetricsUrl", "http://central/metrics");
        doReturn(NetworkProbeService.parseLatency("rtt min/avg/max/mdev = 0.213/0.257/0.301/0.044 ms"))
                .doReturn(-1.0).when(probe).probeLatency("10.212.14.89");
        doReturn(NetworkProbeService.parseBandwidth("{\"end\":{\"sum_received\":{\"bits_per_second\":5.5e7}}}"))
                .doReturn(-1L).when(probe).probeBandwidth("10.212.14.89");
        server.expect(requestTo("http://central/metrics"))
                .andExpect(content().json("[{\"sourceNode\":\"master-88\",\"targetNode\":\"master-89\","
                        + "\"latencyMs\":0.257,\"bandwidthBps\":55000000}]"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://central/metrics"))
                .andExpect(content().json("[{\"sourceNode\":\"master-88\",\"targetNode\":\"master-89\","
                        + "\"latencyMs\":null,\"bandwidthBps\":null}]"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        probe.probeAndPushNetworkMetrics();
        probe.probeAndPushNetworkMetrics();
        server.verify();
    }
}

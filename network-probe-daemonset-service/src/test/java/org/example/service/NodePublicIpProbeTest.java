package org.example.service;

import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.example.utils.PublicIpv4;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NodePublicIpProbeTest {
    private final KubernetesClient k8s = mock(KubernetesClient.class);
    @SuppressWarnings("unchecked")
    private final NonNamespaceOperation<Node, NodeList, Resource<Node>> nodeApi = mock(NonNamespaceOperation.class);
    @SuppressWarnings("unchecked")
    private final Resource<Node> localNode = mock(Resource.class);
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final NodePublicIpProbe probe = new NodePublicIpProbe(k8s, http, "cluster-a", "node-a",
            "https://api-ipv4.ip.sb/ip", "http://central/api/network/nodes/public-ip");

    @BeforeEach
    void bindNodeResource() {
        when(k8s.nodes()).thenReturn(nodeApi);
        when(nodeApi.withName("node-a")).thenReturn(localNode);
    }

    private void knownNode() {
        when(localNode.get()).thenReturn(new NodeBuilder()
                .withNewMetadata().withName("node-a").withUid("uid-a").endMetadata().build());
    }

    @Test
    void reportsTheLocalNodesQueriedIpAndExactIdentity() {
        knownNode();
        server.expect(requestTo("https://api-ipv4.ip.sb/ip"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", "topic4-network-probe/1.0"))
                .andRespond(withSuccess("8.8.8.8\n", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://central/api/network/nodes/public-ip"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"clusterId\":\"cluster-a\",\"nodeName\":\"node-a\",\"k8sUid\":\"uid-a\",\"publicIp\":\"8.8.8.8\"}"))
                .andRespond(withSuccess());
        probe.probeAndPushPublicIp();
        server.verify();
    }

    @Test
    void lookupFailureDoesNotPublishAnEmptyValue() {
        knownNode();
        server.expect(requestTo("https://api-ipv4.ip.sb/ip"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertDoesNotThrow(probe::probeAndPushPublicIp);
        server.verify();
    }

    @Test
    void invalidResponseIsNotReported() {
        knownNode();
        server.expect(requestTo("https://api-ipv4.ip.sb/ip"))
                .andRespond(withSuccess("<html>blocked</html>", MediaType.TEXT_HTML));
        probe.probeAndPushPublicIp();
        server.verify();
    }

    @Test
    void unknownLocalIdentitySkipsLookupAndReport() {
        when(localNode.get()).thenReturn(null);
        probe.probeAndPushPublicIp();
        server.verify();
    }

    @Test
    void rejectsNonIpResponsesAndNonPublicAddresses() {
        assertEquals("8.8.4.4", PublicIpv4.normalize(" 8.8.4.4\n"));
        for (String value : new String[]{null, "", "ip.sb", "10.213.0.1", "127.0.0.1", "172.16.0.1",
                "192.168.1.1", "169.254.169.254", "0.0.0.0", "100.64.0.1", "224.0.0.1", "255.255.255.255",
                "192.0.2.1", "198.51.100.1", "203.0.113.1", "999.1.1.1", "8.8.8.8\n1.1.1.1", "<html>Error</html>"}) {
            assertNull(PublicIpv4.normalize(value), value);
        }
    }
}

package org.example.service;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.dto.NodePublicIpDto;
import org.example.utils.PublicIpv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.Proxy;

@Service
public class NodePublicIpProbe {
    private static final Logger log = LoggerFactory.getLogger(NodePublicIpProbe.class);
    private final KubernetesClient k8sClient;
    private final RestTemplate http;
    private final String clusterId;
    private final String nodeName;
    private final String lookupUrl;
    private final String reportUrl;

    @Autowired
    public NodePublicIpProbe(KubernetesClient k8sClient,
            @Value("${local.cluster.id:in-cluster-default}") String clusterId,
            @Value("${local.node.name}") String nodeName,
            @Value("${probe.public-ip.url:https://api-ipv4.ip.sb/ip}") String lookupUrl,
            @Value("${central.public-ip.url:http://practice-server-svc:8080/api/network/nodes/public-ip}") String reportUrl) {
        this(k8sClient, directHttpClient(), clusterId, nodeName, lookupUrl, reportUrl);
    }

    NodePublicIpProbe(KubernetesClient k8sClient, RestTemplate http, String clusterId,
                      String nodeName, String lookupUrl, String reportUrl) {
        this.k8sClient = k8sClient;
        this.http = http;
        this.clusterId = clusterId;
        this.nodeName = nodeName;
        this.lookupUrl = lookupUrl;
        this.reportUrl = reportUrl;
    }

    private static RestTemplate directHttpClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        // Query the node's own egress, not a configured HTTP proxy's address.
        factory.setProxy(Proxy.NO_PROXY);
        return new RestTemplate(factory);
    }

    @Scheduled(initialDelayString = "${probe.public-ip.initial-delay-ms:10000}",
            fixedDelayString = "${probe.public-ip.interval-ms:600000}")
    public void probeAndPushPublicIp() {
        try {
            Node node = k8sClient.nodes().withName(nodeName).get();
            if (node == null || node.getMetadata() == null || node.getMetadata().getUid() == null) {
                log.warn("Skipping public IP probe: node identity unavailable for {}", nodeName);
                return;
            }
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "topic4-network-probe/1.0");
            String response = http.exchange(lookupUrl, HttpMethod.GET,
                    new HttpEntity<Void>(headers), String.class).getBody();
            String ip = PublicIpv4.normalize(response);
            if (ip == null) {
                log.warn("Public IP lookup did not return a public IPv4 for {}; retaining last result", nodeName);
                return;
            }
            NodePublicIpDto report = new NodePublicIpDto();
            report.setClusterId(clusterId);
            report.setNodeName(nodeName);
            report.setK8sUid(node.getMetadata().getUid());
            report.setPublicIp(ip);
            http.postForEntity(reportUrl, report, String.class);
            log.info("Reported public egress IPv4 for {}", nodeName);
        } catch (Exception failure) {
            // Do not publish null or turn an IP lookup failure into a node availability failure.
            log.warn("Public IP probe/report failed for {}: {}; retaining last result", nodeName, failure.getMessage());
        }
    }
}

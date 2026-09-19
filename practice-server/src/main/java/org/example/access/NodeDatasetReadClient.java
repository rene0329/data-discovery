package org.example.access;

import org.example.entity.NodeManagement;
import org.example.exception.RegistrationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class NodeDatasetReadClient {
    private final RestTemplate restTemplate;
    private final int port;

    public NodeDatasetReadClient(RestTemplate restTemplate,
            @Value("${dispatch.data-discovery.port:8080}") int port) {
        this.restTemplate = restTemplate;
        this.port = port;
    }

    public NodeReadOutcome read(NodeManagement consumer, NodeReadCommand command, String nodeToken) {
        if (consumer == null || consumer.getInternalIp() == null || consumer.getInternalIp().trim().isEmpty()) {
            throw RegistrationException.conflict("consumer node Agent has no reachable address");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (nodeToken != null && !nodeToken.isEmpty()) headers.setBearerAuth(nodeToken);
        try {
            return restTemplate.postForObject("http://" + consumer.getInternalIp() + ":" + port
                    + "/data-discovery/access/read", new HttpEntity<>(command, headers), NodeReadOutcome.class);
        } catch (RestClientException error) {
            throw RegistrationException.conflict("NODE_READ_FAILED", "consumer node read failed: " + error.getMessage());
        }
    }
}

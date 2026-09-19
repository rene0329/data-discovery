package org.example.security.aggregation.coordinator;

import org.example.security.aggregation.SecureAggregationProtocol;
import org.example.security.aggregation.SecureAggregationWire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class SecureAggregationWorkerClient {
    private final RestTemplate restTemplate;

    @Autowired
    public SecureAggregationWorkerClient(SecureAggregationProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(500, properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Math.max(500, properties.getReadTimeoutMs()));
        this.restTemplate = new RestTemplate(requestFactory);
    }

    SecureAggregationWorkerClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public SecureAggregationWire.PrepareResponse prepare(
            SecureAggregationProperties.Participant participant,
            SecureAggregationWire.PrepareRequest request) {
        return post(participant, "prepare", request.getRunId(), request,
                SecureAggregationWire.PrepareResponse.class);
    }

    public SecureAggregationWire.ContributionResponse contribute(
            SecureAggregationProperties.Participant participant,
            SecureAggregationWire.ContributionRequest request) {
        return post(participant, "contribute", request.getRunId(), request,
                SecureAggregationWire.ContributionResponse.class);
    }

    private <T> T post(SecureAggregationProperties.Participant participant, String phase,
                       String runId, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Aggregation-Auth", SecureAggregationProtocol.workerAuthTag(
                participant.getAuthSecret(), phase, runId, participant.getId()));
        String baseUrl = participant.getBaseUrl().endsWith("/")
                ? participant.getBaseUrl().substring(0, participant.getBaseUrl().length() - 1)
                : participant.getBaseUrl();
        String url = baseUrl + "/secure-aggregation/worker/runs/" + runId + "/" + phase;
        ResponseEntity<T> response = restTemplate.postForEntity(url,
                new HttpEntity<>(body, headers), responseType);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("participant " + participant.getId()
                    + " returned an empty or unsuccessful response");
        }
        return response.getBody();
    }
}

package org.example.factory;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;

public class JobCreationResult {
    private final Job job;
    private final KubernetesClient client;
    private final String selectedNodeName;

    public JobCreationResult(Job job, KubernetesClient client, String selectedNodeName) {
        this.job = job;
        this.client = client;
        this.selectedNodeName = selectedNodeName;
    }

    public Job getJob() {
        return job;
    }

    public KubernetesClient getClient() {
        return client;
    }

    public String getSelectedNodeName() {
        return selectedNodeName;
    }
}

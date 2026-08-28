package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.CreateTaskRequest;
import org.example.dto.registration.ResourceRequirements;
import org.example.dto.registration.TaskCreated;
import org.example.entity.RegisteredDataset;
import org.example.entity.RuntimeImage;
import org.example.entity.TaskManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.example.mapper.TaskManagementMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TaskV1Service {
    private final DatasetRegistrationMapper datasetMapper;
    private final RuntimeImageMapper imageMapper;
    private final TaskManagementMapper taskMapper;
    private final RegistrationAuditMapper auditMapper;
    private final K8sTaskOrchestratorService orchestrator;
    private final ObjectMapper objectMapper;

    public TaskV1Service(DatasetRegistrationMapper datasetMapper,
                         RuntimeImageMapper imageMapper,
                         TaskManagementMapper taskMapper,
                         RegistrationAuditMapper auditMapper,
                         K8sTaskOrchestratorService orchestrator,
                         ObjectMapper objectMapper) {
        this.datasetMapper = datasetMapper;
        this.imageMapper = imageMapper;
        this.taskMapper = taskMapper;
        this.auditMapper = auditMapper;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    public TaskCreated create(CreateTaskRequest request, String requestId) {
        String existingResourceId = auditMapper.findResourceIdByRequest("TASK", "CREATE", requestId);
        if (existingResourceId != null) {
            return new TaskCreated(Integer.valueOf(existingResourceId), "ACCEPTED");
        }
        validate(request);
        List<RegisteredDataset> datasets = request.getDatasetIds().stream()
                .map(this::requireActiveDataset)
                .collect(Collectors.toList());
        validateImages(datasets, request.getRuntimeImageId());

        TaskManagement task = TaskManagement.builder()
                .taskName(request.getTaskName().trim())
                .selectedData(datasets.stream().map(RegisteredDataset::getName).collect(Collectors.toList()).toString())
                .datasetIdsJson(writeJson(request.getDatasetIds()))
                .runtimeImageId(request.getRuntimeImageId())
                .resourceOverridesJson(writeJson(request.getResourceOverrides()))
                .status("已接收")
                .createTime(LocalDateTime.now())
                .build();
        taskMapper.submitData(task);
        auditMapper.insert("TASK", String.valueOf(task.getTaskId()), "CREATE", "system", requestId,
                writeJson(request));
        orchestrator.executeRegisteredTask(task.getTaskId(), request.getDatasetIds(),
                request.getRuntimeImageId(), request.getResourceOverrides());
        return new TaskCreated(task.getTaskId(), "ACCEPTED");
    }

    private RegisteredDataset requireActiveDataset(Long datasetId) {
        RegisteredDataset dataset = datasetMapper.findDatasetById(datasetId);
        if (dataset == null) throw RegistrationException.notFound("registered dataset not found: " + datasetId);
        if (!"ACTIVE".equals(dataset.getStatus())) {
            throw RegistrationException.conflict("dataset is not ACTIVE: " + datasetId);
        }
        if (datasetMapper.countAvailableReplicas(datasetId) == 0) {
            throw RegistrationException.conflict("dataset has no available replica: " + datasetId);
        }
        return dataset;
    }

    private void validateImages(List<RegisteredDataset> datasets, Long explicitImageId) {
        if (explicitImageId != null) {
            requireUsableImage(explicitImageId);
            return;
        }
        for (RegisteredDataset dataset : datasets) {
            if (dataset.getDefaultRuntimeImageId() == null) {
                throw RegistrationException.conflict("dataset has no default runtime image: " + dataset.getDatasetId());
            }
            requireUsableImage(dataset.getDefaultRuntimeImageId());
        }
    }

    private RuntimeImage requireUsableImage(Long imageId) {
        RuntimeImage image = imageMapper.findById(imageId);
        if (image == null) throw RegistrationException.notFound("runtime image not found: " + imageId);
        if (!"READY".equals(image.getStatus()) || !Boolean.TRUE.equals(image.getEnabled())
                || image.getResolvedDigest() == null || image.getResolvedDigest().trim().isEmpty()) {
            throw RegistrationException.conflict("runtime image is not verified and enabled: " + imageId);
        }
        return image;
    }

    private void validate(CreateTaskRequest request) {
        if (request == null) throw RegistrationException.invalid("request body is required");
        if (request.getTaskName() == null || request.getTaskName().trim().isEmpty()) {
            throw RegistrationException.invalid("taskName is required");
        }
        if (request.getDatasetIds() == null || request.getDatasetIds().isEmpty()) {
            throw RegistrationException.invalid("datasetIds must not be empty");
        }
        Set<Long> unique = new HashSet<>(request.getDatasetIds());
        if (unique.size() != request.getDatasetIds().size() || unique.contains(null)) {
            throw RegistrationException.invalid("datasetIds must be unique and non-null");
        }
        ResourceRequirements resources = request.getResourceOverrides();
        if (resources != null && (negative(resources.getCpu()) || negative(resources.getMemoryGi())
                || negative(resources.getGpu()))) {
            throw RegistrationException.invalid("resourceOverrides values must not be negative");
        }
    }

    private boolean negative(Double value) { return value != null && value < 0; }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON value", e);
        }
    }
}

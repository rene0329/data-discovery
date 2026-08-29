package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.RegisterRuntimeImageRequest;
import org.example.dto.registration.ResourceRequirements;
import org.example.dto.registration.RuntimeImageView;
import org.example.dto.registration.UpdateRuntimeImageRequest;
import org.example.entity.RuntimeImage;
import org.example.exception.RegistrationException;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RuntimeImageRegistrationService {
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<List<String>>() { };

    private final RuntimeImageMapper mapper;
    private final RegistrationAuditMapper auditMapper;
    private final RuntimeImagePullVerifier pullVerifier;
    private final ObjectMapper objectMapper;

    public RuntimeImageRegistrationService(RuntimeImageMapper mapper,
                                           RegistrationAuditMapper auditMapper,
                                           RuntimeImagePullVerifier pullVerifier,
                                           ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.auditMapper = auditMapper;
        this.pullVerifier = pullVerifier;
        this.objectMapper = objectMapper;
    }

    public List<RuntimeImageView> list(String query, String status) {
        validateQuery(query);
        validateStatus(status);
        String normalizedStatus = status == null ? null : status.trim().toUpperCase();
        return mapper.list(query, normalizedStatus).stream().map(this::toView).collect(Collectors.toList());
    }

    public RuntimeImageView get(Long imageId) {
        return toView(requireImage(imageId));
    }

    @Transactional
    public RuntimeImageView register(RegisterRuntimeImageRequest request, String requestId) {
        String existingResourceId = auditMapper.findResourceIdByRequest("RUNTIME_IMAGE", "REGISTER", requestId);
        if (existingResourceId != null) return get(Long.valueOf(existingResourceId));
        validate(request);
        if (mapper.findByName(request.getName().trim()) != null) {
            throw RegistrationException.conflict("runtime image name already exists");
        }
        RuntimeImage image = new RuntimeImage();
        apply(image, request);
        image.setStatus("DRAFT");
        image.setEnabled(false);
        image.setRowVersion(0);
        mapper.insert(image);
        audit(String.valueOf(image.getRuntimeImageId()), "REGISTER", requestId, writeJson(request));
        return get(image.getRuntimeImageId());
    }

    @Transactional
    public RuntimeImageView update(Long imageId, UpdateRuntimeImageRequest request, String requestId) {
        if (request == null || request.getRowVersion() == null) {
            throw RegistrationException.invalid("rowVersion is required");
        }
        validate(request);
        RuntimeImage image = requireImage(imageId);
        RuntimeImage sameName = mapper.findByName(request.getName().trim());
        if (sameName != null && !sameName.getRuntimeImageId().equals(imageId)) {
            throw RegistrationException.conflict("runtime image name already exists");
        }
        apply(image, request);
        image.setRowVersion(request.getRowVersion());
        if (mapper.update(image) != 1) {
            throw RegistrationException.conflict("runtime image was modified by another request");
        }
        audit(String.valueOf(imageId), "UPDATE", requestId, writeJson(request));
        return get(imageId);
    }

    @Transactional(noRollbackFor = RegistrationException.class)
    public RuntimeImageView verify(Long imageId, String requestId) {
        RuntimeImage image = requireImage(imageId);
        mapper.updateStatus(imageId, "VERIFYING", false, null, "verification in progress", false);
        try {
            RuntimeImagePullVerifier.VerificationResult result =
                    pullVerifier.verify(image.getImageRef(), image.getPullSecretRef());
            mapper.updateStatus(imageId, "READY", false, result.getDigest(), result.getMessage(), true);
            audit(String.valueOf(imageId), "VERIFY", requestId, result.getMessage());
            return get(imageId);
        } catch (RuntimeException e) {
            String internalMessage = safeMessage(e);
            String publicMessage = publicFailureMessage(internalMessage);
            mapper.updateStatus(imageId, "INVALID", false, null, publicMessage, false);
            audit(String.valueOf(imageId), "VERIFY_FAILED", requestId, internalMessage);
            throw RegistrationException.invalid(publicMessage,
                    "runtime image verification failed: " + publicMessage);
        }
    }

    @Transactional
    public RuntimeImageView activate(Long imageId, String requestId) {
        RuntimeImage image = requireImage(imageId);
        if (!"READY".equals(image.getStatus()) || image.getResolvedDigest() == null
                || image.getResolvedDigest().trim().isEmpty()) {
            throw RegistrationException.conflict("runtime image must be verified before activation");
        }
        mapper.updateStatus(imageId, "READY", true, image.getResolvedDigest(),
                image.getVerificationMessage(), false);
        audit(String.valueOf(imageId), "ACTIVATE", requestId, null);
        return get(imageId);
    }

    @Transactional
    public RuntimeImageView disable(Long imageId, String requestId) {
        RuntimeImage image = requireImage(imageId);
        mapper.updateStatus(imageId, image.getStatus(), false, image.getResolvedDigest(),
                image.getVerificationMessage(), false);
        audit(String.valueOf(imageId), "DISABLE", requestId, null);
        return get(imageId);
    }

    @Transactional
    public void unregister(Long imageId, String requestId) {
        requireImage(imageId);
        int bindings = mapper.countDatasetBindings(imageId);
        if (bindings > 0) {
            throw RegistrationException.conflict("runtime image is bound to " + bindings + " dataset(s)");
        }
        mapper.softDelete(imageId);
        audit(String.valueOf(imageId), "UNREGISTER", requestId, null);
    }

    private RuntimeImage requireImage(Long imageId) {
        RuntimeImage image = mapper.findById(imageId);
        if (image == null) throw RegistrationException.notFound("runtime image not found");
        return image;
    }

    private void validate(RegisterRuntimeImageRequest request) {
        if (request == null) throw RegistrationException.invalid("request body is required");
        requireText(request.getName(), "name");
        requireText(request.getImageRef(), "imageRef");
        requireText(request.getTaskType(), "taskType");
        requireText(request.getModelType(), "modelType");
        requireText(request.getDataPathTemplate(), "dataPathTemplate");
        if (request.getCommand() == null || request.getCommand().isEmpty()) {
            throw RegistrationException.invalid("command must not be empty");
        }
        ResourceRequirements resources = request.getDefaultResources();
        if (resources != null && (negative(resources.getCpu()) || negative(resources.getMemoryGi())
                || negative(resources.getGpu()))) {
            throw RegistrationException.invalid("defaultResources values must not be negative");
        }
    }

    private boolean negative(Double value) {
        return value != null && value < 0;
    }

    private void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw RegistrationException.invalid(field + " is required");
        }
    }

    private void apply(RuntimeImage image, RegisterRuntimeImageRequest request) {
        ResourceRequirements resources = request.getDefaultResources();
        image.setName(request.getName().trim());
        image.setImageRef(request.getImageRef().trim());
        image.setTaskType(request.getTaskType().trim());
        image.setModelType(request.getModelType().trim());
        image.setCommandJson(writeJson(request.getCommand()));
        image.setArgsTemplateJson(writeJson(request.getArgsTemplate() == null
                ? Collections.emptyList() : request.getArgsTemplate()));
        image.setDataPathTemplate(request.getDataPathTemplate().trim());
        image.setDefaultCpu(resources == null ? null : resources.getCpu());
        image.setDefaultMemoryGi(resources == null ? null : resources.getMemoryGi());
        image.setDefaultGpu(resources == null ? null : resources.getGpu());
        image.setPullSecretRef(trimToNull(request.getPullSecretRef()));
    }

    private RuntimeImageView toView(RuntimeImage image) {
        return RuntimeImageView.from(image, readList(image.getCommandJson()), readList(image.getArgsTemplateJson()));
    }

    private List<String> readList(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON value", e);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private String publicFailureMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        if (normalized.contains("forbidden") || normalized.contains("cannot create resource")
                || normalized.contains("cannot delete resource")) {
            return "IMAGE_VERIFY_FORBIDDEN";
        }
        if (normalized.contains("imagepullbackoff") || normalized.contains("errimagepull")
                || normalized.contains("cannot pull image")) {
            return "IMAGE_PULL_FAILED";
        }
        if (normalized.contains("timed out")) return "IMAGE_VERIFY_TIMEOUT";
        if (normalized.contains("different digests")) return "IMAGE_DIGEST_MISMATCH";
        return "IMAGE_VERIFY_FAILED";
    }

    private void validateQuery(String query) {
        if (query != null && query.length() > 200) {
            throw RegistrationException.invalid("query must not exceed 200 characters");
        }
    }

    private void validateStatus(String status) {
        if (status == null || status.trim().isEmpty()) return;
        String normalized = status.trim().toUpperCase();
        if (!java.util.Arrays.asList("DRAFT", "VERIFYING", "READY", "INVALID", "DISABLED")
                .contains(normalized)) {
            throw RegistrationException.invalid("unsupported runtime image status: " + status);
        }
    }

    private void audit(String resourceId, String action, String requestId, String detail) {
        auditMapper.insert("RUNTIME_IMAGE", resourceId, action, "system", requestId, detail);
    }
}

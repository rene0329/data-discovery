package org.example.dto.registration;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.example.entity.RuntimeImage;

import java.time.LocalDateTime;
import java.util.List;

public class RuntimeImageView {
    private Long imageId;
    private String name;
    private String imageRef;
    private String resolvedDigest;
    private String taskType;
    private String modelType;
    private List<String> command;
    private List<String> argsTemplate;
    private String dataPathTemplate;
    private ResourceRequirements defaultResources;
    private String pullSecretRef;
    private String status;
    private Boolean enabled;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime verifiedAt;
    private String verificationMessage;
    private Integer rowVersion;

    public static RuntimeImageView from(RuntimeImage entity, List<String> command, List<String> argsTemplate) {
        RuntimeImageView view = new RuntimeImageView();
        view.imageId = entity.getRuntimeImageId();
        view.name = entity.getName();
        view.imageRef = entity.getImageRef();
        view.resolvedDigest = entity.getResolvedDigest();
        view.taskType = entity.getTaskType();
        view.modelType = entity.getModelType();
        view.command = command;
        view.argsTemplate = argsTemplate;
        view.dataPathTemplate = entity.getDataPathTemplate();
        ResourceRequirements resources = new ResourceRequirements();
        resources.setCpu(entity.getDefaultCpu());
        resources.setMemoryGi(entity.getDefaultMemoryGi());
        resources.setGpu(entity.getDefaultGpu());
        view.defaultResources = resources;
        view.pullSecretRef = entity.getPullSecretRef();
        view.status = entity.getStatus();
        view.enabled = entity.getEnabled();
        view.verifiedAt = entity.getVerifiedAt();
        view.verificationMessage = entity.getVerificationMessage();
        view.rowVersion = entity.getRowVersion();
        return view;
    }

    public Long getImageId() { return imageId; }
    public String getName() { return name; }
    public String getImageRef() { return imageRef; }
    public String getResolvedDigest() { return resolvedDigest; }
    public String getTaskType() { return taskType; }
    public String getModelType() { return modelType; }
    public List<String> getCommand() { return command; }
    public List<String> getArgsTemplate() { return argsTemplate; }
    public String getDataPathTemplate() { return dataPathTemplate; }
    public ResourceRequirements getDefaultResources() { return defaultResources; }
    public String getPullSecretRef() { return pullSecretRef; }
    public String getStatus() { return status; }
    public Boolean getEnabled() { return enabled; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public String getVerificationMessage() { return verificationMessage; }
    public Integer getRowVersion() { return rowVersion; }
}

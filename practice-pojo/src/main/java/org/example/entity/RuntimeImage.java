package org.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeImage {
    private Long runtimeImageId;
    private String legacyProfileId;
    private String name;
    private String imageRef;
    private String resolvedDigest;
    private String taskType;
    private String modelType;
    private String commandJson;
    private String argsTemplateJson;
    private String dataPathTemplate;
    private Double defaultCpu;
    private Double defaultMemoryGi;
    private Double defaultGpu;
    private String pullSecretRef;
    private String status;
    private Boolean enabled;
    private LocalDateTime verifiedAt;
    private String verificationMessage;
    private Integer rowVersion;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Controller/Service 层使用的结构化视图，不直接映射数据库列。
    private List<String> command;
    private List<String> argsTemplate;
}

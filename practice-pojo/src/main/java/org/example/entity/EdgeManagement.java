// src/main/java/org/example/entity/EdgeManagement.java
package org.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.sql.Timestamp;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeManagement implements Serializable {
    private Integer edgeId;
    private Integer sourceId;
    private Integer targetId;
    private Long bandwidth;
    private Double latency;
    private String status;
    private Timestamp measurementTime;
    // 重要：这里**不**包含 sourceNodeName 和 targetNodeName，因为它们不是数据库字段。
    // 我们会在 Service 层组合这些信息，或通过 DTO 返回。
}

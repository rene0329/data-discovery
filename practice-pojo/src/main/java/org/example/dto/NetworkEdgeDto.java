
package org.example.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NetworkEdgeDto implements Serializable {
    private Integer edgeId;
    private Integer sourceId;
    private Integer targetId;
    private String sourceNodeName; // <--- 为前端新增
    private String targetNodeName; // <--- 为前端新增
    private Long bandwidth;
    private Double latency;
    private String status;
    private Long measurementTime; // <--- 转换为 Long (Unix 毫秒时间戳)
}

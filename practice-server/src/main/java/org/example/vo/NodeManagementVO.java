package org.example.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeManagementVO {
    private String nodeName;

    // ✅ CPU 原始值
    private Double currentCpu;
    private Double maxCpu;

    // ✅ 内存原始值
    private Double currentMemory;
    private Double maxMemory;

    // ❌ 移除 storage 相关字段
    // private Double storage;
}
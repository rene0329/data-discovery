//package org.example.entity;
//
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//
//@Data
//@Builder
//@NoArgsConstructor
//@AllArgsConstructor
//public class DataManagement {
//
//    private Integer dataId;
//
//    private String dataName;
//
//    private Double dataSize;
//
//    private String dataHeat;
//
//    private Integer dataStatus;
//
//    private String dataServer;
//
//    private String backupServer;
//
//    private Integer dataCount;
//
//    private String dataDescription;
//
//    private String requiredCpu;
//
//    private String requiredMemory;
//}

package org.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataManagement {

    private Integer dataId;

    private String dataName;

    // --- 字段类型调整 ---
    // data_size 应该存储精确的字节数，改为 Long。
    private Long dataSize; // <--- 类型由 Double 改为 Long

    private Double dataHeat;

    private Integer dataStatus;

    // --- data_server 字段处理 ---
    // 原始 data_server (String) 可以保留，如果它有其他用途。
    // 但为了与 node_management 表建立强关联，我们新增 dataNodeId。
    // 如果 data_server 的唯一作用就是指向节点，可以考虑移除，但为了兼容性先保留。
    private String dataServer; // 保留，如果它有业务意义

    private String backupServer;

    private Integer dataCount;

    private String dataDescription;

    // requiredCpu 和 requiredMemory 最好也是 Double 类型，而不是 String，方便计算

    private Double requiredCpu;
    private Double requiredMemory;

    // --- 新增物理文件元数据字段 ---
    private String filePath;            // 文件在宿主机上的完整路径 (对应 `file_path` 列)
    private Timestamp lastModifiedTime; // 文件的最后修改时间 (对应 `last_modified_time` 列)
    private String fileType;            // 文件类型推断 (对应 `file_type` 列)
    private String md5Hash;             // 文件内容的MD5哈希 (对应 `md5_hash` 列，可选)

    private Integer dataNodeId;         // <--- 新增，外键，关联到 `node_management.node_id` (对应 `data_node_id` 列)

    // 显式 getter/setter，避免 Lombok 处理异常时出现方法缺失
    public Double getRequiredCpu() {
        return requiredCpu;
    }

    public void setRequiredCpu(Double requiredCpu) {
        this.requiredCpu = requiredCpu;
    }

    public Double getRequiredMemory() {
        return requiredMemory;
    }

    public void setRequiredMemory(Double requiredMemory) {
        this.requiredMemory = requiredMemory;
    }
}

package org.example.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 数据传输对象 (DTO)，用于封装网络探测 DaemonSet 发送的网络测量数据。
 * 作为 /api/network/metrics API 的请求体。
 */
@Data // Lombok 注解，自动生成 Getter, Setter, toString, equals, hashCode
@Builder // Lombok 注解，提供链式构建器模式
@NoArgsConstructor // Lombok 注解，生成无参构造函数
@AllArgsConstructor // Lombok 注解，生成全参构造函数
public class NetworkMetricDto implements Serializable {
    private static final long serialVersionUID = 1L; // 推荐添加，用于序列化兼容性

    private String sourceNode;     // 源节点名称 (K8s node name)，对应 network-probe 所在节点
    private String targetNode;     // 目标节点名称 (K8s node name)，对应被探测的节点
    private Long bandwidthBps;     // 带宽 (bits per second)，使用 Long 避免精度问题并存储大数值
    private Double latencyMs;       // 延迟 (milliseconds)，使用 Float 可以有小数精度
    private Long measurementTime;  // 测量时间 (Unix 毫秒时间戳)，使用 Long 存储大数值时间戳
}

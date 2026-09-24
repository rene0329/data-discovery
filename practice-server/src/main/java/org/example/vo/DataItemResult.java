package org.example.vo;

import lombok.Data;

import java.time.Instant;

@Data // 使用 Lombok 自动生成 Getters, Setters, toString等
public class DataItemResult {
    private String scheduleT1; // P2P调度方案描述 (e.g., "data1: nodeA -> nodeC")
    private String scheduleT2; // 中心化调度方案描述 (e.g., "data1: nodeA -> node-central")
    private double t1Seconds;  // P2P方案耗时
    private double t2Seconds;  // 中心化方案耗时
    private String sourceNodeName; // 实际读取的副本所在节点
    private String targetNodeName; // Job 实际运行的计算节点
    private Long preparationMs;    // 本数据集自身的数据准备（搬运）耗时
    private Instant preparationStartedAt;
    private Instant preparationReadyAt;
    private Instant computeStartedAt;
    private Instant computeFinishedAt;
    private String actualNodeName;
    private Long inputBytes;
    private String inputChecksumSha256;
    private String outputChecksumSha256;
    /** Better compute nodes skipped for lack of free resources (IN_PLACE); null when the first choice fit. */
    private String placementNote;
}

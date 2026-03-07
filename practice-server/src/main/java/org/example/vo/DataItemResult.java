package org.example.vo;

import lombok.Data;

@Data // 使用 Lombok 自动生成 Getters, Setters, toString等
public class DataItemResult {
    private String scheduleT1; // P2P调度方案描述 (e.g., "data1: nodeA -> nodeC")
    private String scheduleT2; // 中心化调度方案描述 (e.g., "data1: nodeA -> node-central")
    private double t1Seconds;  // P2P方案耗时
    private double t2Seconds;  // 中心化方案耗时
}

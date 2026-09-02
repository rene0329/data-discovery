package org.example.dto.registration;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Legacy physical-file editing is limited to heat, addressed by its physical ID. */
public class LegacyHeatUpdate {
    private Integer dataId;
    private Double dataHeat;
    public Integer getDataId() { return dataId; }
    public void setDataId(Integer dataId) { this.dataId = dataId; }
    public Double getDataHeat() { return dataHeat; }
    public void setDataHeat(Double dataHeat) { this.dataHeat = dataHeat; }
    @JsonAnySetter
    public void unsupported(String name, Object value) {
        throw new IllegalArgumentException("only dataId and dataHeat are supported; unsupported field: " + name);
    }
}

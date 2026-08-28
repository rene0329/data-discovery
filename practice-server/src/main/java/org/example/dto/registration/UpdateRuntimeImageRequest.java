package org.example.dto.registration;

public class UpdateRuntimeImageRequest extends RegisterRuntimeImageRequest {
    private Integer rowVersion;

    public Integer getRowVersion() { return rowVersion; }
    public void setRowVersion(Integer rowVersion) { this.rowVersion = rowVersion; }
}

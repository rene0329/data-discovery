package org.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetMetadata {
    private Long datasetId;
    private String metadataVersion;
    private String digestAlgorithm;
    private String digestValue;
    private String schemaJson;
    private String profileJson;
    private String sourceJson;
    private String schedulingHintsJson;
    private String labelsJson;
}

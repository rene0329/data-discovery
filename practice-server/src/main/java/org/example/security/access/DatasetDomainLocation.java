package org.example.security.access;

/** One (dataset, domain) pair: the dataset has a located replica on a node of that domain's site. */
public class DatasetDomainLocation {
    private Long datasetId;
    private Long domainId;
    private String domainName;

    public DatasetDomainLocation() {
    }

    public DatasetDomainLocation(Long datasetId, Long domainId, String domainName) {
        this.datasetId = datasetId;
        this.domainId = domainId;
        this.domainName = domainName;
    }

    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
    public Long getDomainId() { return domainId; }
    public void setDomainId(Long domainId) { this.domainId = domainId; }
    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }
}

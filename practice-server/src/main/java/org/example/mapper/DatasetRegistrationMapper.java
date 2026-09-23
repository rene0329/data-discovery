package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.entity.DatasetDiscoveryCandidate;
import org.example.entity.DatasetReplica;
import org.example.entity.DatasetMetadata;
import org.example.entity.RegisteredDataset;

import java.util.List;

@Mapper
public interface DatasetRegistrationMapper {
    int upsertCandidate(DatasetDiscoveryCandidate candidate);
    DatasetDiscoveryCandidate findCandidateById(Long candidateId);
    DatasetDiscoveryCandidate findCandidateByNodePath(@Param("nodeId") Integer nodeId,
                                                      @Param("filePath") String filePath);
    List<DatasetDiscoveryCandidate> listCandidates(@Param("query") String query,
                                                   @Param("nodeId") Integer nodeId,
                                                   @Param("onlyUnregistered") boolean onlyUnregistered);
    int markCandidateRegistered(@Param("candidateId") Long candidateId,
                                @Param("datasetId") Long datasetId);
    int markCandidateAvailability(@Param("nodeId") Integer nodeId,
                                  @Param("filePath") String filePath,
                                  @Param("availability") String availability);
    int updateCandidateIntegrity(@Param("nodeId") Integer nodeId,
                                 @Param("filePath") String filePath,
                                 @Param("sizeBytes") Long sizeBytes,
                                 @Param("checksumAlgorithm") String checksumAlgorithm,
                                 @Param("checksum") String checksum,
                                 @Param("availability") String availability,
                                 @Param("verified") boolean verified);

    int insertDataset(RegisteredDataset dataset);
    int refreshHeat(@Param("halfLifeHours") double halfLifeHours, @Param("threshold") double threshold);
    int recordHeatAccess(@Param("datasetId") Long datasetId,
                         @Param("halfLifeHours") double halfLifeHours, @Param("threshold") double threshold,
                         @Param("accessAlpha") double accessAlpha, @Param("maxHeat") double maxHeat);
    RegisteredDataset findDatasetById(Long datasetId);
    RegisteredDataset findDatasetByCodeAndVersion(@Param("datasetCode") String datasetCode,
                                                   @Param("datasetVersion") String datasetVersion);
    List<RegisteredDataset> listDatasets(@Param("query") String query,
                                         @Param("status") String status);
    int updateDataset(RegisteredDataset dataset);
    int updateDatasetStatus(@Param("datasetId") Long datasetId,
                            @Param("status") String status,
                            @Param("verificationMessage") String verificationMessage,
                            @Param("verified") boolean verified);
    int bindRuntimeImage(@Param("datasetId") Long datasetId,
                         @Param("runtimeImageId") Long runtimeImageId);
    int softDeleteDataset(Long datasetId);
    int countTaskReferences(@Param("datasetId") Long datasetId,
                            @Param("datasetName") String datasetName);
    int countActiveMigrationReferences(@Param("datasetId") Long datasetId,
                                       @Param("legacyDataId") Integer legacyDataId);
    int countActiveSchedulingReferences(@Param("datasetId") Long datasetId);

    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM privacy_compute_input_snapshot WHERE dataset_id=#{datasetId}")
    int countPrivacyComputeReferences(@Param("datasetId") Long datasetId);

    Long lockDataset(Long datasetId);
    Integer lockStorageNode(Integer nodeId);
    int countActiveTaskReferences(@Param("datasetId") Long datasetId, @Param("datasetName") String datasetName);
    int countOtherSchedulingReferences(@Param("datasetId") Long datasetId, @Param("planId") Long planId);
    int countStorageSlots(Integer nodeId);
    int countReservedStorageSlots(Integer nodeId);

    int insertReplica(DatasetReplica replica);
    DatasetReplica findReplicaById(Long replicaId);
    DatasetReplica findReplicaByDatasetNodePath(@Param("datasetId") Long datasetId,
                                                @Param("nodeId") Integer nodeId,
                                                @Param("filePath") String filePath);
    DatasetReplica findReplicaByNodePath(@Param("nodeId") Integer nodeId,
                                         @Param("filePath") String filePath);
    List<DatasetReplica> listReplicas(Long datasetId);
    int updateReplicaAvailability(@Param("replicaId") Long replicaId,
                                  @Param("availability") String availability,
                                  @Param("verified") boolean verified);
    int updateReplicaIntegrity(@Param("replicaId") Long replicaId,
                               @Param("sizeBytes") Long sizeBytes,
                               @Param("checksumAlgorithm") String checksumAlgorithm,
                               @Param("checksum") String checksum,
                               @Param("availability") String availability,
                               @Param("verificationMessage") String verificationMessage,
                               @Param("verified") boolean verified);
    int countAvailableReplicas(Long datasetId);
    int deleteReplica(@Param("replicaId") Long replicaId, @Param("datasetId") Long datasetId);
    int deleteCandidateByNodePath(@Param("nodeId") Integer nodeId, @Param("filePath") String filePath);

    int upsertDatasetMetadata(DatasetMetadata metadata);
    DatasetMetadata findDatasetMetadata(Long datasetId);
    int setDatasetAuthority(@Param("datasetId") Long datasetId,
                            @Param("authoritativeSizeBytes") Long authoritativeSizeBytes,
                            @Param("digestAlgorithm") String digestAlgorithm,
                            @Param("digestValue") String digestValue);
}

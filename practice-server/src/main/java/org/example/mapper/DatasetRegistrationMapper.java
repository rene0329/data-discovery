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

    int insertDataset(RegisteredDataset dataset);
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
    int countLegacyTaskReferences(@Param("datasetName") String datasetName);

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
    int countAvailableReplicas(Long datasetId);

    int upsertDatasetMetadata(DatasetMetadata metadata);
    DatasetMetadata findDatasetMetadata(Long datasetId);
}

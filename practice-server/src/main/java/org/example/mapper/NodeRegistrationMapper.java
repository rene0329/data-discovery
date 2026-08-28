package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.entity.NodeDiscoveryCandidate;

import java.util.List;

@Mapper
public interface NodeRegistrationMapper {
    int upsertCandidate(NodeDiscoveryCandidate candidate);
    NodeDiscoveryCandidate findCandidateById(Long candidateId);
    NodeDiscoveryCandidate findCandidateByClusterAndUid(@Param("clusterId") String clusterId,
                                                         @Param("k8sUid") String k8sUid);
    List<NodeDiscoveryCandidate> listCandidates(@Param("query") String query,
                                                @Param("clusterId") String clusterId,
                                                @Param("onlyUnregistered") boolean onlyUnregistered);
    int markCandidateRegistered(@Param("candidateId") Long candidateId,
                                @Param("registeredNodeId") Integer registeredNodeId);
    int markCandidateOffline(@Param("clusterId") String clusterId,
                             @Param("k8sUid") String k8sUid);
}

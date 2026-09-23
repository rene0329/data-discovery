package org.example.security.access;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * Where datasets are: a dataset belongs to the domain(s) of the nodes currently
 * holding its replicas (node_management.site_code = collaboration_domain.site_code),
 * so its domain follows copy/move scheduling without any stored attribute.
 *
 * <p>A replica counts unless its stored availability is MISSING (left behind on
 * the source of a move) or VERIFY_FAILED (a failed copy); every other state,
 * including UNAVAILABLE while the node is offline, still says the data is
 * there. Disabled domains, unregistered nodes and soft-deleted datasets do not
 * count. registered_dataset.owner_user_id / owner_domain_id (the retired per-dataset
 * holder) play no part.
 */
@Mapper
public interface DatasetDomainMapper {
    String LOCATION_SELECT = "SELECT DISTINCT r.dataset_id, d.domain_id, d.domain_code, d.name AS domain_name " +
            "FROM dataset_replica r " +
            "JOIN registered_dataset rd ON rd.dataset_id = r.dataset_id AND rd.deleted_at IS NULL " +
            "JOIN node_management n ON n.node_id = r.node_id AND n.deleted_at IS NULL " +
            "JOIN collaboration_domain d ON d.site_code = n.site_code AND d.enabled = 1 " +
            "WHERE r.availability NOT IN ('MISSING', 'VERIFY_FAILED') ";
    String LOCATION_ORDER = "ORDER BY r.dataset_id, d.domain_id";

    @Select({"<script>",
            LOCATION_SELECT,
            "AND r.dataset_id IN",
            "<foreach collection='datasetIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            LOCATION_ORDER,
            "</script>"})
    List<DatasetDomainLocation> findLocationDomains(@Param("datasetIds") Collection<Long> datasetIds);

    @Select(LOCATION_SELECT + LOCATION_ORDER)
    List<DatasetDomainLocation> findAllLocationDomains();
}

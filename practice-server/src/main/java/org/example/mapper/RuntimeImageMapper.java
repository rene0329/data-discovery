package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.entity.RuntimeImage;

import java.util.List;

@Mapper
public interface RuntimeImageMapper {
    int insert(RuntimeImage image);
    RuntimeImage findById(Long runtimeImageId);
    RuntimeImage findByName(String name);
    List<RuntimeImage> list(@Param("query") String query, @Param("status") String status);
    int update(RuntimeImage image);
    int updateStatus(@Param("runtimeImageId") Long runtimeImageId,
                     @Param("status") String status,
                     @Param("enabled") boolean enabled,
                     @Param("resolvedDigest") String resolvedDigest,
                     @Param("verificationMessage") String verificationMessage,
                     @Param("verified") boolean verified);
    int softDelete(Long runtimeImageId);
    int countDatasetBindings(Long runtimeImageId);
}

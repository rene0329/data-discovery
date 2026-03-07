package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.entity.TrainingProfile;

@Mapper
public interface TrainingProfileMapper {
    TrainingProfile findByDatasetName(@Param("datasetName") String datasetName);

    TrainingProfile findDefaultByTaskType(@Param("taskType") String taskType);
}

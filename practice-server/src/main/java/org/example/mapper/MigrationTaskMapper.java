package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.entity.MigrationTask;

@Mapper
public interface MigrationTaskMapper {
    int insert(MigrationTask task);

    int updateLifecycle(MigrationTask task);
}

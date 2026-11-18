package com.you.youbackend.moment.mapper;

import com.you.youbackend.moment.model.RelatedFeedRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface RelatedFeedMapper {

    List<RelatedFeedRow> selectPage(
            @Param("userId") UUID userId,
            @Param("sharedAt") LocalDateTime sharedAt,
            @Param("id") UUID id,
            @Param("rowCount") int rowCount
    );
}

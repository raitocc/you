package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.model.MomentFeedCursor;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.UUID;

/**
 * 访问瞬间主表。
 */
@Mapper
public interface MomentMapper extends BaseMapper<Moment> {
    /**
     * 锁定有效瞬间，串行化同一瞬间的全量编辑。
     */
    default Moment selectForUpdate(UUID id) {
        return selectOne(Wrappers.<Moment>lambdaQuery().eq(Moment::getId, id).last("FOR UPDATE"));
    }

    /**
     * 全量更新可编辑字段，允许显式清空可空字段。
     */
    default void updateContent(Moment moment) {
        update(Wrappers.<Moment>lambdaUpdate()
                .eq(Moment::getId, moment.getId())
                .set(Moment::getTitle, moment.getTitle())
                .set(Moment::getBody, moment.getBody())
                .set(Moment::getMood, moment.getMood())
                .set(Moment::getMemoryTime, moment.getMemoryTime())
                .set(Moment::getLatitude, moment.getLatitude())
                .set(Moment::getLongitude, moment.getLongitude())
                .set(Moment::getLocationName, moment.getLocationName())
                .set(Moment::getLocationAddress, moment.getLocationAddress())
                .set(Moment::getUpdatedAt, moment.getUpdatedAt()));
    }

    /**
     * 读取本人瞬间流中位于游标之后的卡片数据。
     */
    default List<Moment> selectFeed(UUID userId, MomentFeedCursor cursor, int rowCount) {
        var query = Wrappers.<Moment>lambdaQuery()
                .select(Moment::getId, Moment::getTitle, Moment::getBody, Moment::getMood, Moment::getMemoryTime)
                .eq(Moment::getAuthorUserId, userId);
        if (cursor != null) {
            query.and(boundary -> boundary.lt(Moment::getMemoryTime, cursor.memoryTime())
                    .or(sameTime -> sameTime.eq(Moment::getMemoryTime, cursor.memoryTime())
                            .lt(Moment::getId, cursor.id())));
        }
        return selectList(query.orderByDesc(Moment::getMemoryTime, Moment::getId).last("LIMIT " + rowCount));
    }
}

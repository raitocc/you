package com.you.youbackend.album.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.album.entity.Album;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 时光主表读写，查询沿用全局逻辑删除过滤。 */
@Mapper
public interface AlbumMapper extends BaseMapper<Album> {
    /**
     * 关联调整后只刷新时光的修改时间，其他字段不动。
     *
     * @param id        已校验归属的有效时光 ID
     * @param updatedAt 本次统一的 UTC 毫秒时间
     * @return 更新行数
     */
    default int updateUpdatedAtById(UUID id, LocalDateTime updatedAt) {
        // 关联变化只影响修改时间，不覆盖名称、介绍或封面。
        return update(Wrappers.<Album>lambdaUpdate().eq(Album::getId, id)
                .set(Album::getUpdatedAt, updatedAt));
    }

    /**
     * 按 ID 查时光，含已软删除的（自定义 SQL，绕过 MP 的逻辑删除过滤），给删除流程做幂等判断用。
     *
     * @param id 时光 ID
     * @return 完整实体，不存在返回 null
     */
    // TODO 用 SELECT * 好吗？
    @Select("SELECT * FROM album WHERE id = #{id}")
    Album selectIncludingDeletedById(@Param("id") UUID id);

    /**
     * 更新时光的展示信息（名称、介绍、封面）和修改时间；逐字段显式 set，null 也能清空。
     * 所有者、整理模式和创建时间不动，已删除记录自动跳过。
     *
     * @param album 已填充最终字段及修改时间的实体，按 ID 更新
     * @return 更新行数
     */
    default int updateDisplayInfo(Album album) {
        // 仅修改展示信息和审计时间，保留所有者、整理设置及创建时间。
        return update(Wrappers.<Album>lambdaUpdate()
                .eq(Album::getId, album.getId())
                .set(Album::getName, album.getName())
                .set(Album::getDescription, album.getDescription())
                .set(Album::getCoverFileId, album.getCoverFileId())
                .set(Album::getUpdatedAt, album.getUpdatedAt()));
    }

    /**
     * 软删除有效时光，同时把 deletedAt、updatedAt 记成本次删除时间；已删除记录自动跳过。
     *
     * @param id        已校验归属的时光 ID
     * @param deletedAt 本次统一的 UTC 毫秒时间
     */
    default void softDeleteById(UUID id, LocalDateTime deletedAt) {
        // 同时记录删除和修改时间，重复请求不覆盖原删除时间。
        update(Wrappers.<Album>lambdaUpdate()
                .eq(Album::getId, id)
                .set(Album::getDeletedAt, deletedAt)
                .set(Album::getUpdatedAt, deletedAt));
    }

    /**
     * 查本人时光流里排在游标后面的一页，给列表分页用。
     *
     * <p>按“创建时间、ID 倒序”排，游标条件是“时间更早，或时间相同且 ID 更小”（和排序配套，不重不漏）。
     * 只读查询，无副作用。</p>
     *
     * @param userId     所有者 ID，只查本人的有效时光
     * @param cursorTime 游标的 UTC 时间，null 表示首屏
     * @param cursorId   游标记录 ID，和 cursorTime 成对传入
     * @param rowCount   本页大小 + 1，多的那条只用来判断下一页
     * @return 新到旧排列的完整实体，最多 rowCount 条
     */
    default List<Album> selectPageAfterCursor(UUID userId, LocalDateTime cursorTime, UUID cursorId, int rowCount) {
        // 1. 基础查询只读取当前用户的有效时光。
        var query = Wrappers.<Album>lambdaQuery().eq(Album::getOwnerUserId, userId);
        // 2. 有游标时读取更早时间，或相同时间下更小的 ID；首屏不加边界。
        if (cursorTime != null) {
            query.and(boundary -> boundary.lt(Album::getCreatedAt, cursorTime)
                    .or(sameTime -> sameTime.eq(Album::getCreatedAt, cursorTime)
                            .lt(Album::getId, cursorId)));
        }
        // 3. 统一排序并多读取一条，供业务层判断下一页。
        return selectList(query.orderByDesc(Album::getCreatedAt, Album::getId).last("LIMIT " + rowCount));
    }

}

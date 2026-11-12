package com.you.youbackend.gathering.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.model.GatheringStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 同行主表读写，复用全局 UUID 映射与软删除配置。 */
@Mapper
public interface GatheringMapper extends BaseMapper<Gathering> {
    /** 查询包含已解散记录的同行，用于校验创建者和识别重复解散。 */
    @Select("""
            SELECT id, created_at, updated_at, creator_user_id, name, description,
                   status, invite_enabled, invite_code, face_code, deleted_at
            FROM gathering WHERE id = #{id}
            """)
    Gathering selectIncludingDeleted(@Param("id") UUID id);

    /** 将有效同行结束，同时关闭邀请；已有成员和内容保留。 */
    default void endGathering(UUID id, LocalDateTime updatedAt) {
        // 同一条更新保存结束状态和邀请开关。
        update(Wrappers.<Gathering>lambdaUpdate().eq(Gathering::getId, id)
                .set(Gathering::getStatus, GatheringStatus.ENDED)
                .set(Gathering::getInviteEnabled, false).set(Gathering::getUpdatedAt, updatedAt));
    }

    /** 软删除同行，解散时间同时作为修改时间；重复请求由业务层识别。 */
    default void dissolveGathering(UUID id, LocalDateTime deletedAt) {
        // 沿用全局逻辑删除过滤，仅更新有效同行。
        update(Wrappers.<Gathering>lambdaUpdate().eq(Gathering::getId, id)
                .set(Gathering::getDeletedAt, deletedAt).set(Gathering::getUpdatedAt, deletedAt));
    }


    /**
     * 分页读取本人已加入的有效同行，按同行创建时间、ID 倒序排列。
     * @param userId 当前用户 ID
     * @param cursorTime 游标同行创建时间，UTC；null 表示首屏
     * @param cursorId 游标同行 ID，与创建时间成对提供
     * @param rowCount 页大小加一，用于判断下一页
     * @return 有序完整同行实体
     */
    default List<Gathering> selectPage(UUID userId, LocalDateTime cursorTime, UUID cursorId, int rowCount) {
        // 1. 过滤已解散同行，成员子查询限定本人仍有有效成员关系。
        var query = new QueryWrapper<Gathering>()
                .isNull("g.deleted_at")
                .exists("""
                        SELECT 1
                        FROM gathering_member m
                        WHERE m.gathering_id = g.id
                          AND m.user_id = {0}
                          AND m.deleted_at IS NULL
                        """, userId);
        // 2. 有游标时读取更早时间，或相同时间下更小的 ID；首屏不加边界。
        if (cursorTime != null) {
            query.and(boundary -> boundary.lt("g.created_at", cursorTime)
                    .or(sameTime -> sameTime.eq("g.created_at", cursorTime).lt("g.id", cursorId)));
        }
        // 3. 统一排序，交给固定 SQL 多取一条判断下一页。
        query.orderByDesc("g.created_at", "g.id");
        return selectPageRows(query, rowCount);
    }

    /**
     * 执行同行列表查询，筛选与排序由 selectPage 统一构造。
     * @param query 后端构造的带表别名条件，含有效成员 EXISTS、软删除过滤及排序
     * @param rowCount 已校验页大小加一
     * @return 有序完整同行实体
     */
    @Select("""
            SELECT g.id, g.created_at, g.updated_at, g.creator_user_id, g.name, g.description,
                   g.status, g.invite_enabled, g.invite_code, g.face_code, g.deleted_at
            FROM gathering g
            ${ew.customSqlSegment}
            LIMIT #{rowCount}
            """)
    List<Gathering> selectPageRows(@Param(Constants.WRAPPER) Wrapper<Gathering> query,
                                   @Param("rowCount") int rowCount);

    /**
     * 保存名称、介绍与修改时间，null 介绍显式写入数据库。
     * @param gathering 已校验创建者权限并填充最终字段的有效同行
     * @return 更新行数
     */
    default int updateContent(Gathering gathering) {
        // 只覆盖本次全量编辑范围，保留状态、邀请设置及创建信息。
        return update(Wrappers.<Gathering>lambdaUpdate().eq(Gathering::getId, gathering.getId())
                .set(Gathering::getName, gathering.getName())
                .set(Gathering::getDescription, gathering.getDescription())
                .set(Gathering::getUpdatedAt, gathering.getUpdatedAt()));
    }

    /**
     * 首次登记面对面短码，永久保留已有值。
     * @param gatheringId 已确认存在且当前用户有开启权限的同行 ID
     * @param faceCode 已校验的四位短码
     * @param updatedAt 本次 UTC 毫秒修改时间
     * @return 成功首次登记返回 1，已有短码或记录失效返回 0
     */
    @Update("""
            UPDATE gathering SET face_code = #{faceCode}, updated_at = #{updatedAt}
            WHERE id = #{gatheringId} AND face_code IS NULL AND deleted_at IS NULL
            """)
    int setFaceCodeIfAbsent(@Param("gatheringId") UUID gatheringId, @Param("faceCode") String faceCode,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 从 GEO 候选中筛出仍有效的面对面邀请。
     * @param ids 非空的附近同行 ID 集合
     * @param faceCode 用户输入的短码，必须与数据库永久记录一致
     * @param createdAfter 当前时间减 10 分钟，创建时间须严格晚于此边界
     * @return 邀请开启、短码一致且未过期的完整同行，软删除由全局配置过滤
     */
    default List<Gathering> selectFaceToFaceCandidates(
            Collection<UUID> ids, String faceCode, LocalDateTime createdAfter) {
        // 位置来自 GEO，实际短码与有效期统一按数据库记录判断。
        return selectList(Wrappers.<Gathering>lambdaQuery().in(Gathering::getId, ids)
                .eq(Gathering::getFaceCode, faceCode)
                .eq(Gathering::getInviteEnabled, true)
                .gt(Gathering::getCreatedAt, createdAfter));
    }

    /**
     * 根据长期邀请码读取未解散的同行，邀请开关由业务层判断。
     * @param inviteCode 已通过非空白与长度校验的邀请码
     * @return 完整同行实体，不存在或已解散时为 null
     */
    default Gathering selectByInviteCode(String inviteCode) {
        // 唯一邀请码定位同行，逻辑删除过滤由 MyBatis-Plus 自动补充。
        return selectOne(Wrappers.<Gathering>lambdaQuery().eq(Gathering::getInviteCode, inviteCode));
    }
}

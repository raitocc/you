package com.you.youbackend.gathering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.gathering.entity.Gathering;
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
    /**
     * 读取本人有效成员关系对应的同行首屏。
     * @param userId 当前用户 ID
     * @param rowCount 页大小加一
     * @return 按同行创建时间和 ID 倒序的完整实体
     */
    @Select("""
            SELECT g.id, g.created_at, g.updated_at, g.creator_user_id, g.name, g.description,
                   g.status, g.invite_enabled, g.invite_code, g.face_code, g.deleted_at
            FROM gathering g
            WHERE g.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM gathering_member m
                          WHERE m.gathering_id = g.id AND m.user_id = #{userId} AND m.deleted_at IS NULL)
            ORDER BY g.created_at DESC, g.id DESC LIMIT #{rowCount}
            """)
    List<Gathering> selectFirstPage(@Param("userId") UUID userId, @Param("rowCount") int rowCount);

    /**
     * 读取本人同行后续页，严格排除上一页末项及其之前的位置。
     * @param userId 当前用户 ID
     * @param createdAt 上一页末项创建时间，UTC
     * @param id 上一页末项同行 ID
     * @param rowCount 页大小加一
     * @return 与首屏排序一致的完整实体
     */
    @Select("""
            SELECT g.id, g.created_at, g.updated_at, g.creator_user_id, g.name, g.description,
                   g.status, g.invite_enabled, g.invite_code, g.face_code, g.deleted_at
            FROM gathering g
            WHERE g.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM gathering_member m
                          WHERE m.gathering_id = g.id AND m.user_id = #{userId} AND m.deleted_at IS NULL)
              AND (g.created_at < #{createdAt} OR (g.created_at = #{createdAt} AND g.id < #{id}))
            ORDER BY g.created_at DESC, g.id DESC LIMIT #{rowCount}
            """)
    List<Gathering> selectNextPage(@Param("userId") UUID userId, @Param("createdAt") LocalDateTime createdAt,
                                   @Param("id") UUID id, @Param("rowCount") int rowCount);

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

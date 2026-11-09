package com.you.youbackend.gathering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.gathering.entity.Gathering;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 同行主表读写，复用全局 UUID 映射与软删除配置。 */
@Mapper
public interface GatheringMapper extends BaseMapper<Gathering> {
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
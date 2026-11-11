package com.you.youbackend.circle.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.CircleMember;
import com.you.youbackend.circle.model.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.*;
/** 成员关系读写及分页；所有自定义 SQL 显式排除已离开关系和已解散圈子。 */
@Mapper
public interface CircleMemberMapper extends BaseMapper<CircleMember> {
    /** 判断当前用户有效成员资格，沿用全局逻辑删除。 */
    default boolean isActiveMember(UUID circleId, UUID userId) {
        // 资格只取决于有效成员关系。
        return exists(Wrappers.<CircleMember>lambdaQuery().eq(CircleMember::getCircleId, circleId)
                .eq(CircleMember::getUserId, userId));
    }
    /** 读取首屏；过滤后再按成员关系时间和 ID 倒序截取。 */
    @Select("""
        SELECT c.id, c.name, c.avatar_file_id, m.created_at AS joined_at, m.id AS membership_id
        FROM circle_member m JOIN circle c ON c.id = m.circle_id AND c.deleted_at IS NULL
        WHERE m.user_id = #{userId} AND m.deleted_at IS NULL
        ORDER BY m.created_at DESC, m.id DESC LIMIT #{rowCount}
        """)
    List<CircleListRow> selectFirstPage(@Param("userId") UUID userId, @Param("rowCount") int rowCount);

    /** 后续页采用与排序一致的严格边界，避免同时间记录重复或遗漏。 */
    @Select("""
        SELECT c.id, c.name, c.avatar_file_id, m.created_at AS joined_at, m.id AS membership_id
        FROM circle_member m JOIN circle c ON c.id = m.circle_id AND c.deleted_at IS NULL
        WHERE m.user_id = #{userId} AND m.deleted_at IS NULL
          AND (m.created_at < #{joinedAt} OR (m.created_at = #{joinedAt} AND m.id < #{id}))
        ORDER BY m.created_at DESC, m.id DESC LIMIT #{rowCount}
        """)
    List<CircleListRow> selectNextPage(@Param("userId") UUID userId, @Param("joinedAt") LocalDateTime joinedAt,
                                      @Param("id") UUID id, @Param("rowCount") int rowCount);

    /** 批量聚合成员数量，调用者保证圈子 ID 非空，零成员由业务按 0 补齐。 */
    @Select("""
        <script>
        SELECT circle_id, COUNT(*) AS member_count FROM circle_member
        WHERE deleted_at IS NULL AND circle_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY circle_id
        </script>
        """)
    List<CircleMemberCount> countActiveMembers(@Param("ids") Collection<UUID> ids);
    /** 从指定圈子中查出用户仍是成员且圈子未解散的 ID。传入的 ID 列表不能为空。 */
    default List<UUID> selectActiveCircleIds(UUID userId, Collection<UUID> ids) {
        return selectList(Wrappers.<CircleMember>lambdaQuery()
                .select(CircleMember::getCircleId)
                .eq(CircleMember::getUserId, userId)
                .in(CircleMember::getCircleId, ids)
                .inSql(CircleMember::getCircleId, "SELECT id FROM circle WHERE deleted_at IS NULL"))
                .stream().map(CircleMember::getCircleId).toList();
    }
}

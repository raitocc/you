package com.you.youbackend.circle.mapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.CircleShare;
import com.you.youbackend.circle.model.CircleShareObjectType;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.*;
/** 有效分享的状态更新使用 MyBatis-Plus，历史读写、批量新增及跨表查询使用专用 SQL。 */
@Mapper
public interface CircleShareMapper extends BaseMapper<CircleShare> {
    /** 查同一内容在指定圈子里的全部分享，包括已撤回的记录。圈子 ID 列表不能为空。 */
    @Select("""
        <script>
        SELECT * FROM circle_share WHERE object_type=#{type} AND object_id=#{objectId} AND circle_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<CircleShare> selectHistory(@Param("type") CircleShareObjectType type, @Param("objectId") UUID objectId,
                                  @Param("ids") Collection<UUID> ids);
    /** 按圈子 ID 和分享 ID 查一条分享，包括已撤回的记录。 */
    @Select("SELECT * FROM circle_share WHERE circle_id=#{circleId} AND id=#{shareId}")
    CircleShare selectHistoryById(@Param("circleId") UUID circleId, @Param("shareId") UUID shareId);
    /** 单条语句插入全部新分享，重复键由 MyBatis-Spring 转译，供 Service 返回 409。 */
    @Insert("""
        <script>INSERT INTO circle_share (id,circle_id,object_type,object_id,sharer_user_id,shared_at,created_at,updated_at) VALUES
        <foreach collection="rows" item="s" separator=",">
        (#{s.id},#{s.circleId},#{s.objectType},#{s.objectId},#{s.sharerUserId},#{s.sharedAt},#{s.createdAt},#{s.updatedAt})
        </foreach></script>
        """)
    int insertShares(@Param("rows") List<CircleShare> rows);
    /** 只更新仍处于撤回状态的记录。清空撤回时间，更新分享者和时间，返回更新条数。 */
    @Update("""
        <script>UPDATE circle_share SET deleted_at=NULL, sharer_user_id=#{userId},shared_at=#{now},updated_at=#{now}
        WHERE deleted_at IS NOT NULL AND id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
        """)
    int restoreShares(@Param("ids") List<UUID> ids,@Param("userId") UUID userId,@Param("now") LocalDateTime now);
    /** 给尚未撤回的分享写入撤回时间和更新时间。 */
    default int withdraw(UUID id, LocalDateTime now) {
        return update(withdrawal(now).eq(CircleShare::getId, id));
    }
    /** 查分享列表的第一页，按分享时间和分享 ID 倒序。排除已撤回、原内容已删除、圈子已解散或分享者已离开的记录。 */
    @Select("""
        <script>
        SELECT s.* FROM circle_share s
        JOIN circle c ON c.id=s.circle_id AND c.deleted_at IS NULL
        JOIN circle_member sm ON sm.circle_id=s.circle_id AND sm.user_id=s.sharer_user_id AND sm.deleted_at IS NULL
        WHERE s.circle_id=#{circleId} AND s.deleted_at IS NULL
        AND ((s.object_type='moment' AND EXISTS (SELECT 1 FROM moment m WHERE m.id=s.object_id AND m.deleted_at IS NULL))
        OR (s.object_type='album' AND EXISTS (SELECT 1 FROM album a WHERE a.id=s.object_id AND a.deleted_at IS NULL)))
        ORDER BY s.shared_at DESC, s.id DESC
        LIMIT #{rowCount}
        </script>
        """)
    List<CircleShare> selectFirstPage(@Param("circleId") UUID circleId,@Param("rowCount") int rowCount);
    /** 按第一页的筛选和排序规则，从游标之后继续查。分享时间相同时，只取分享 ID 小于游标 ID 的记录。 */
    @Select("""
        <script>
        SELECT s.* FROM circle_share s
        JOIN circle c ON c.id=s.circle_id AND c.deleted_at IS NULL
        JOIN circle_member sm ON sm.circle_id=s.circle_id AND sm.user_id=s.sharer_user_id AND sm.deleted_at IS NULL
        WHERE s.circle_id=#{circleId} AND s.deleted_at IS NULL
        AND ((s.object_type='moment' AND EXISTS (SELECT 1 FROM moment m WHERE m.id=s.object_id AND m.deleted_at IS NULL))
        OR (s.object_type='album' AND EXISTS (SELECT 1 FROM album a WHERE a.id=s.object_id AND a.deleted_at IS NULL)))
        AND (s.shared_at &lt; #{time} OR (s.shared_at=#{time} AND s.id &lt; #{id}))
        ORDER BY s.shared_at DESC, s.id DESC
        LIMIT #{rowCount}
        </script>
        """)
    List<CircleShare> selectNextPage(@Param("circleId") UUID circleId,@Param("time") LocalDateTime time,@Param("id") UUID id,@Param("rowCount") int rowCount);
    /** 按圈子 ID 和分享 ID 查仍可查看的分享。调用前先检查查看者的成员身份。 */
    @Select("""
        SELECT s.* FROM circle_share s
        JOIN circle c ON c.id=s.circle_id AND c.deleted_at IS NULL
        JOIN circle_member sm ON sm.circle_id=s.circle_id AND sm.user_id=s.sharer_user_id AND sm.deleted_at IS NULL
        WHERE s.circle_id=#{circleId} AND s.deleted_at IS NULL
        AND ((s.object_type='moment' AND EXISTS (SELECT 1 FROM moment m WHERE m.id=s.object_id AND m.deleted_at IS NULL))
        OR (s.object_type='album' AND EXISTS (SELECT 1 FROM album a WHERE a.id=s.object_id AND a.deleted_at IS NULL)))
        AND s.id=#{shareId}
        """)
    CircleShare selectAccessible(@Param("circleId") UUID circleId,@Param("shareId") UUID shareId);
    /**
     * 撤回指定成员在这个圈子里的全部有效分享。
     * TODO 实现退出或移除成员时，在更新成员状态的同一事务里调用，防止重新加入后旧分享恢复可见。
     */
    default int invalidateMemberShares(UUID circleId, UUID userId, LocalDateTime now) {
        return update(withdrawal(now).eq(CircleShare::getCircleId, circleId)
                .eq(CircleShare::getSharerUserId, userId));
    }
    /**
     * 撤回这个圈子里的全部有效分享。
     * TODO 实现解散圈子时，在更新圈子状态的同一事务里调用。
     */
    default int invalidateCircleShares(UUID circleId, LocalDateTime now) {
        return update(withdrawal(now).eq(CircleShare::getCircleId, circleId));
    }

    private static LambdaUpdateWrapper<CircleShare> withdrawal(LocalDateTime now) {
        return Wrappers.<CircleShare>lambdaUpdate()
                .set(CircleShare::getDeletedAt, now)
                .set(CircleShare::getUpdatedAt, now);
    }
}

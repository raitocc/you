package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.you.youbackend.moment.entity.Moment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 瞬间主记录及信息流查询。 */
@Mapper
public interface MomentMapper extends BaseMapper<Moment> {

    @Select("SELECT * FROM moment WHERE id = #{id}")
    Moment findById(UUID id);

    @Select("SELECT * FROM moment WHERE id = #{id} FOR UPDATE")
    Moment lockById(UUID id);

    @Select("""
            <script>
            SELECT m.* FROM moment m
            WHERE m.deleted_at IS NULL
            <choose>
                <when test="related">
                    AND (EXISTS (
                        SELECT 1 FROM gathering g JOIN gathering_member gm ON gm.gathering_id = g.id
                        WHERE g.id = m.gathering_id AND g.deleted_at IS NULL
                          AND gm.user_id = #{userId} AND gm.deleted_at IS NULL
                    ) OR EXISTS (
                        SELECT 1 FROM circle_share s JOIN circle c ON c.id = s.circle_id
                        JOIN circle_member cm ON cm.circle_id = c.id
                        WHERE s.object_type = 'moment' AND s.object_id = m.id
                          AND s.deleted_at IS NULL AND c.deleted_at IS NULL
                          AND cm.user_id = #{userId} AND cm.deleted_at IS NULL
                    ))
                </when>
                <otherwise>AND m.author_user_id = #{userId}</otherwise>
            </choose>
            <if test="afterId != null">
                AND (m.memory_time &lt; #{afterTime}
                    OR (m.memory_time = #{afterTime} AND m.id &lt; #{afterId}))
            </if>
            ORDER BY m.memory_time DESC, m.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Moment> selectFeed(@Param("userId") UUID userId, @Param("related") boolean related,
                            @Param("afterTime") LocalDateTime afterTime, @Param("afterId") UUID afterId,
                            @Param("limit") int limit);

    @Update("""
            UPDATE moment SET gathering_id = #{gatheringId}, title = #{title}, body = #{body},
                mood = #{mood}, memory_time = #{memoryTime}, location_name = #{locationName},
                location_address = #{locationAddress}, latitude = #{latitude}, longitude = #{longitude},
                updated_at = #{updatedAt}, deleted_at = #{deletedAt}
            WHERE id = #{id}
            """)
    int updateContent(Moment moment);
}

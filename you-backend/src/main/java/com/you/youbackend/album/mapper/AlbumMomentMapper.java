package com.you.youbackend.album.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.you.youbackend.album.entity.AlbumMoment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** 时光瞬间关联的批量写入和有效数量查询。 */
@Mapper
public interface AlbumMomentMapper extends BaseMapper<AlbumMoment> {
    /**
     * 一条 SQL 插入本次新建时光的关联，参与调用方事务，不恢复或忽略冲突。
     * @param associations 非空、已去重且完成业务校验的关联，含 ID 和 UTC 审计时间
     * @return 插入行数
     */
    @Insert("""
            <script>
            INSERT INTO album_moment (id, album_id, moment_id, created_at, updated_at)
            VALUES
            <foreach collection="associations" item="item" separator=",">
                (#{item.id}, #{item.albumId}, #{item.momentId}, #{item.createdAt}, #{item.updatedAt})
            </foreach>
            </script>
            """)
    int insertAssociations(@Param("associations") List<AlbumMoment> associations);

    /**
     * 统计指定时光中有效的本人瞬间，显式过滤关联和瞬间的软删除记录。
     * @param albumId 已确认有效且归属当前用户的时光 ID
     * @param userId 时光所有者 ID，排除非本人发布的瞬间
     * @return 当前有效瞬间数量，不依赖创建时的关联数
     */
    @Select("""
            SELECT COUNT(*) FROM album_moment am
            JOIN moment m ON m.id = am.moment_id
            WHERE am.album_id = #{albumId} AND am.deleted_at IS NULL
                AND m.deleted_at IS NULL AND m.author_user_id = #{userId}
            """)
    long countValidMoments(@Param("albumId") UUID albumId, @Param("userId") UUID userId);
}

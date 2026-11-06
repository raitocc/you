package com.you.youbackend.moment.mapper;

import com.you.youbackend.media.entity.MediaFile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 瞬间图片、标签、来源和动态的数据访问。 */
@Mapper
public interface MomentRelationMapper {

    record Target(UUID id, String name) {}

    record ImageFile(UUID fileId, int sortOrder, String storageKey, String mimeType) {}

    @Select("""
            SELECT f.id, f.owner_user_id, f.storage_key, f.mime_type
            FROM media_file f WHERE f.id = #{id} AND f.deleted_at IS NULL FOR UPDATE
            """)
    MediaFile lockImage(UUID id);

    @Select("""
            SELECT i.file_id, i.sort_order, f.storage_key, f.mime_type
            FROM moment_image i JOIN media_file f ON f.id = i.file_id AND f.deleted_at IS NULL
            WHERE i.moment_id = #{momentId} AND i.deleted_at IS NULL ORDER BY i.sort_order
            """)
    List<ImageFile> selectImages(UUID momentId);

    @Select("""
            SELECT file_id FROM moment_image
            WHERE moment_id = #{momentId} AND deleted_at IS NULL ORDER BY sort_order
            """)
    List<UUID> selectImageIds(UUID momentId);

    @Select("""
            SELECT tag_name FROM moment_tag
            WHERE moment_id = #{momentId} AND deleted_at IS NULL ORDER BY id
            """)
    List<String> selectTags(UUID momentId);

    @Update("""
            UPDATE moment_image SET deleted_at = #{now}, updated_at = #{now}
            WHERE moment_id = #{momentId} AND deleted_at IS NULL
            """)
    void removeImages(@Param("momentId") UUID momentId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO moment_image (id, moment_id, file_id, sort_order, created_at, updated_at)
            VALUES (#{id}, #{momentId}, #{value}, #{sortOrder}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE sort_order = #{sortOrder}, deleted_at = NULL, updated_at = #{now}
            """)
    void saveImage(@Param("id") UUID id, @Param("momentId") UUID momentId, @Param("value") UUID value,
                  @Param("sortOrder") int sortOrder, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE moment_tag SET deleted_at = #{now}, updated_at = #{now}
            WHERE moment_id = #{momentId} AND deleted_at IS NULL
            """)
    void removeTags(@Param("momentId") UUID momentId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO moment_tag (id, moment_id, tag_name, created_at, updated_at)
            VALUES (#{id}, #{momentId}, #{value}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE deleted_at = NULL, updated_at = #{now}
            """)
    void saveTag(@Param("id") UUID id, @Param("momentId") UUID momentId, @Param("value") String value,
                  @Param("now") LocalDateTime now);

    @Select("""
            SELECT g.id, g.name FROM gathering g
            JOIN gathering_member gm ON gm.gathering_id = g.id
            WHERE g.id = #{id} AND g.deleted_at IS NULL
              AND gm.user_id = #{userId} AND gm.deleted_at IS NULL
            FOR UPDATE
            """)
    Target lockJourney(@Param("id") UUID id, @Param("userId") UUID userId);

    @Select("""
            SELECT c.id, c.name FROM circle c JOIN circle_member cm ON cm.circle_id = c.id
            WHERE c.id = #{id} AND c.deleted_at IS NULL
              AND cm.user_id = #{userId} AND cm.deleted_at IS NULL
            FOR UPDATE
            """)
    Target lockCircle(@Param("id") UUID id, @Param("userId") UUID userId);

    @Select("""
            SELECT g.id, g.name FROM gathering g WHERE g.id = #{id} AND g.deleted_at IS NULL
            AND (#{own} OR EXISTS (
                SELECT 1 FROM gathering_member gm WHERE gm.gathering_id = g.id
                  AND gm.user_id = #{userId} AND gm.deleted_at IS NULL
            ))
            """)
    Target selectJourney(@Param("id") UUID id, @Param("userId") UUID userId, @Param("own") boolean own);

    @Select("""
            SELECT c.id, c.name FROM circle_share s JOIN circle c ON c.id = s.circle_id
            WHERE s.object_type = 'moment' AND s.object_id = #{momentId}
              AND s.deleted_at IS NULL AND c.deleted_at IS NULL
              AND (#{own} OR EXISTS (
                  SELECT 1 FROM circle_member cm WHERE cm.circle_id = c.id
                    AND cm.user_id = #{userId} AND cm.deleted_at IS NULL
              ))
            ORDER BY c.id
            """)
    List<Target> selectCircles(@Param("momentId") UUID momentId, @Param("userId") UUID userId,
                               @Param("own") boolean own);

    @Select("""
            SELECT circle_id FROM circle_share
            WHERE object_type = 'moment' AND object_id = #{momentId} AND deleted_at IS NULL
            ORDER BY circle_id FOR UPDATE
            """)
    List<UUID> lockSharedCircleIds(UUID momentId);

    @Update("""
            UPDATE circle_share SET deleted_at = #{now}, updated_at = #{now}
            WHERE object_type = 'moment' AND object_id = #{momentId} AND circle_id = #{circleId}
              AND deleted_at IS NULL
            """)
    void removeShare(@Param("momentId") UUID momentId, @Param("circleId") UUID circleId,
                     @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO circle_share (id, circle_id, object_type, object_id, sharer_user_id,
                                      shared_at, created_at, updated_at)
            VALUES (#{id}, #{circleId}, 'moment', #{momentId}, #{actor}, #{now}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE deleted_at = NULL, shared_at = #{now},
                updated_at = #{now}, sharer_user_id = #{actor}
            """)
    void saveShare(@Param("id") UUID id, @Param("circleId") UUID circleId, @Param("momentId") UUID momentId,
                   @Param("actor") UUID actor, @Param("now") LocalDateTime now);

    @Select("""
            SELECT a.id, a.name FROM album_moment am JOIN album a ON a.id = am.album_id
            WHERE am.moment_id = #{momentId} AND am.deleted_at IS NULL AND a.deleted_at IS NULL
              AND (a.owner_user_id = #{userId} OR EXISTS (
                  SELECT 1 FROM circle_share s JOIN circle c ON c.id = s.circle_id
                  JOIN circle_member cm ON cm.circle_id = c.id
                  WHERE s.object_type = 'album' AND s.object_id = a.id
                    AND s.deleted_at IS NULL AND c.deleted_at IS NULL
                    AND cm.user_id = #{userId} AND cm.deleted_at IS NULL
              ))
            ORDER BY a.id
            """)
    List<Target> selectAlbums(@Param("momentId") UUID momentId, @Param("userId") UUID userId);

    @Update("""
            UPDATE album_moment SET deleted_at = #{now}, updated_at = #{now}
            WHERE moment_id = #{momentId} AND deleted_at IS NULL
            """)
    void removeAlbums(@Param("momentId") UUID momentId, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT m.user_id FROM
            <choose>
                <when test="journey">gathering_member m WHERE m.gathering_id = #{sourceId}</when>
                <otherwise>circle_member m WHERE m.circle_id = #{sourceId}</otherwise>
            </choose>
            AND m.deleted_at IS NULL AND m.user_id != #{actor}
            AND EXISTS (SELECT 1 FROM app_user u WHERE u.id = m.user_id AND u.deleted_at IS NULL)
            </script>
            """)
    List<UUID> selectRecipients(@Param("sourceId") UUID sourceId, @Param("actor") UUID actor,
                                @Param("journey") boolean journey);

    @Insert("""
            INSERT INTO user_inbox (id, created_at, updated_at, recipient_user_id,
                actor_user_id, source_type, source_id, object_type, object_id)
            VALUES (#{id}, #{now}, #{now}, #{recipient}, #{actor}, #{sourceType}, #{sourceId},
                'moment', #{momentId})
            """)
    void insertInbox(@Param("id") UUID id, @Param("recipient") UUID recipient, @Param("actor") UUID actor,
                     @Param("sourceType") String sourceType, @Param("sourceId") UUID sourceId,
                     @Param("momentId") UUID momentId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE user_inbox SET deleted_at = #{now}, updated_at = #{now}
            WHERE object_type = 'moment' AND object_id = #{momentId} AND deleted_at IS NULL
              AND source_type = #{sourceType} AND source_id = #{sourceId}
            """)
    void removeInbox(@Param("momentId") UUID momentId, @Param("sourceType") String sourceType,
                     @Param("sourceId") UUID sourceId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE user_inbox SET deleted_at = #{now}, updated_at = #{now}
            WHERE object_type = 'moment' AND object_id = #{momentId} AND deleted_at IS NULL
            """)
    void removeAllInbox(@Param("momentId") UUID momentId, @Param("now") LocalDateTime now);
}

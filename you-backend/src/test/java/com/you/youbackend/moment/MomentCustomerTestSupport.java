package com.you.youbackend.moment;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.gathering.model.GatheringStatus;
import com.you.youbackend.CustomerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 瞬间客户测试使用的业务夹具和查询操作。 */
abstract class MomentCustomerTestSupport extends CustomerTestSupport {
    static final String MEMORY_TIME = "2026-11-20T10:00:00Z";
    static final String PUBLISHED_AT = "2026-11-21T10:00:00Z";

    UUID a;
    UUID b;
    UUID c;
    UUID d;

    /**
     * 【在每个测试之前】：独立准备已注册用户 A、B、C、D。
     */
    @BeforeEach
    void createUsers() {
        a = user("A");
        b = user("B");
        c = user("C");
        d = user("D");
    }

    /**
     * 【在每个测试之后】：清理本测试类专用数据库内的夹具和已提交写入。
     */
    @AfterEach
    void cleanDatabase() {
        for (String table : new String[]{
                "notification", "user_inbox", "circle_share", "album_moment", "moment_image", "moment_tag",
                "moment", "album", "circle_member", "circle", "gathering_member", "gathering", "media_file", "app_user"
        }) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /**
     * 【辅助方法】：读取本人瞬间列表。
     */
    ResultActions yours(UUID user) throws Exception {
        return success(request(user, get("/moments/yours")));
    }

    /**
     * 【辅助方法】：读取原瞬间详情。
     */
    ResultActions detail(UUID user, UUID moment) throws Exception {
        return request(user, get("/moments/{id}", moment));
    }

    /**
     * 【辅助方法】：通过同行入口读取瞬间详情。
     */
    ResultActions gatheringDetail(UUID user, UUID gathering, UUID moment) throws Exception {
        return request(user, get("/gatherings/{id}/moments/{moment}", gathering, moment));
    }

    /**
     * 【辅助方法】：通过已知的圈子分享入口读取瞬间详情。
     */
    ResultActions circleDetail(UUID user, UUID circle, UUID share) throws Exception {
        return request(user, get("/circles/{id}/shares/{share}/moment", circle, share));
    }

    /**
     * 【辅助方法】：退出预置的圈子。
     */
    void leaveCircle(UUID user, UUID circle) throws Exception {
        success(request(user, delete("/circles/{id}/members/me", circle)));
    }

    /**
     * 【辅助方法】：生成用于测试夹具的 UUIDv7。
     */
    UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    /**
     * 【辅助方法】：插入纯文字瞬间并使用默认记忆时间和发布时间。
     */
    UUID moment(UUID author, String body) {
        return moment(author, body, MEMORY_TIME, PUBLISHED_AT, null, false);
    }

    /**
     * 【辅助方法】：按给定时间、关联和状态插入瞬间。
     */
    UUID moment(UUID author, String body, String memoryTime, String createdAt, UUID gathering, boolean deleted) {
        UUID id = newId();
        jdbc.update("""
                INSERT INTO moment (id, author_user_id, body, memory_time, created_at, updated_at, gathering_id, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(id), bytes(author), body, utc(memoryTime), utc(createdAt), utc(createdAt),
                gathering == null ? null : bytes(gathering), deleted ? utc(createdAt) : null);
        return id;
    }

    /**
     * 【辅助方法】：填入瞬间的标题、心情和地点。
     */
    void describeMoment(UUID moment, String title, String mood, String location) {
        jdbc.update("UPDATE moment SET title = ?, mood = ?, location_name = ? WHERE id = ?",
                title, mood, location, bytes(moment));
    }

    /**
     * 【辅助方法】：插入归指定用户所有的已就绪图片。
     */
    UUID image(UUID owner) {
        UUID id = newId();
        jdbc.update("""
                INSERT INTO media_file (id, owner_user_id, storage_key, mime_type, byte_size, created_at, updated_at)
                VALUES (?, ?, ?, 'image/jpeg', 12, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bytes(id), bytes(owner), "customer-test/" + id + ".jpg");
        return id;
    }

    /**
     * 【辅助方法】：按展示顺序关联瞬间图片。
     */
    void images(UUID moment, UUID... files) {
        for (int index = 0; index < files.length; index++) {
            jdbc.update("""
                    INSERT INTO moment_image (id, moment_id, file_id, sort_order, created_at, updated_at)
                    VALUES (?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, bytes(newId()), bytes(moment), bytes(files[index]), index + 1);
        }
    }

    /**
     * 【辅助方法】：预置瞬间的标签。
     */
    void tags(UUID moment, String... names) {
        for (String name : names) {
            jdbc.update("""
                    INSERT INTO moment_tag (id, moment_id, tag_name, created_at, updated_at)
                    VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, bytes(newId()), bytes(moment), name);
        }
    }

    /**
     * 【辅助方法】：预置有效的同行及成员。
     */
    UUID gathering(UUID owner, UUID... members) {
        return gathering(owner, GatheringStatus.ONGOING, members);
    }

    /**
     * 【辅助方法】：预置指定状态的同行及成员。
     */
    UUID gathering(UUID owner, GatheringStatus status, UUID... members) {
        UUID id = newId();
        jdbc.update("""
                INSERT INTO gathering (id, creator_user_id, name, status, invite_code, created_at, updated_at)
                VALUES (?, ?, '测试同行', ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bytes(id), bytes(owner), status.getCode(), inviteCode(id));
        for (UUID member : members) {
            jdbc.update("""
                    INSERT INTO gathering_member (id, gathering_id, user_id, created_at, updated_at)
                    VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, bytes(newId()), bytes(id), bytes(member));
        }
        return id;
    }

    /**
     * 【辅助方法】：预置有效的圈子及成员。
     */
    UUID circle(UUID owner, UUID... members) {
        UUID id = newId();
        jdbc.update("""
                INSERT INTO circle (id, creator_user_id, name, invite_code, created_at, updated_at)
                VALUES (?, ?, '测试圈子', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bytes(id), bytes(owner), inviteCode(id));
        for (UUID member : members) {
            jdbc.update("""
                    INSERT INTO circle_member (id, circle_id, user_id, created_at, updated_at)
                    VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, bytes(newId()), bytes(id), bytes(member));
        }
        return id;
    }

    /**
     * 【辅助方法】：预置包含指定瞬间的手动时光。
     */
    UUID album(UUID owner, String name, UUID... moments) {
        UUID id = newId();
        jdbc.update("""
                INSERT INTO album (id, owner_user_id, name, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bytes(id), bytes(owner), name);
        for (UUID moment : moments) {
            jdbc.update("""
                    INSERT INTO album_moment (id, album_id, moment_id, created_at, updated_at)
                    VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, bytes(newId()), bytes(id), bytes(moment));
        }
        return id;
    }

    /**
     * 【辅助方法】：预置圈子分享及接收者的有效或已撤回记录。
     */
    UUID circleShare(UUID circle, UUID actor, String type, UUID object, String sharedAt, boolean revoked, UUID recipient) {
        UUID id = newId();
        LocalDateTime time = utc(sharedAt);
        jdbc.update("""
                INSERT INTO circle_share (id, circle_id, sharer_user_id, object_type, object_id, shared_at, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(id), bytes(circle), bytes(actor), type, bytes(object), time, time, time, revoked ? time : null);
        if (recipient != null) {
            receivedShare("circle", circle, actor, type, object, sharedAt, revoked, recipient, id);
        }
        return id;
    }

    /**
     * 【辅助方法】：预置同行成员收到的分享记录。
     */
    void gatheringShare(UUID gathering, UUID actor, UUID moment, String sharedAt, UUID recipient) {
        receivedShare("gathering", gathering, actor, "moment", moment, sharedAt, false, recipient, null);
    }

    /**
     * 【辅助方法】：插入接收者当前可见或已撤回的分享记录。
     */
    private void receivedShare(String sourceType, UUID source, UUID actor, String type, UUID object, String sharedAt,
                               boolean revoked, UUID recipient, UUID share) {
        LocalDateTime time = utc(sharedAt);
        jdbc.update("""
                INSERT INTO user_inbox (id, recipient_user_id, actor_user_id, source_type, source_id,
                                        object_type, object_id, shared_at, share_id, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(newId()), bytes(recipient), bytes(actor), sourceType, bytes(source), type, bytes(object), time,
                share == null ? null : bytes(share), time, time, revoked ? time : null);
    }

    /**
     * 【辅助方法】：登记具备独立身份的测试用户。
     */
    private UUID user(String nickname) {
        UUID id = newId();
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, nickname, created_at, updated_at)
                VALUES (?, ?, 'unused', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bytes(id), "customer-" + id, nickname);
        return id;
    }

    /**
     * 【辅助方法】：根据 UUID 截取并去除连字符生成邀请码
     */
    private static String inviteCode(UUID id) {
        return id.toString().substring(19).replace("-", "");
    }

    /**
     * 【辅助方法】：将时间转换为迁移脚本定义的 UTC 数据库时间。
     */
    private static LocalDateTime utc(String time) {
        return LocalDateTime.ofInstant(Instant.parse(time), ZoneOffset.UTC);
    }

    /**
     * 【辅助方法】：将 UUID 转换为 16 字节数组以便写入数据库
     */
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}

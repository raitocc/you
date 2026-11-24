package com.you.youbackend.moment;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** MOMENT-DELETE 删除瞬间 */
class DeleteMomentCustomerTest extends MomentCustomerTestSupport {
    /**
     * 作者的瞬间同时被时光、同行和圈子引用时，删除该瞬间，成功
     */
    @Test
    void shouldRemoveMomentFromAllViewsWhenAuthorDeletesIt() throws Exception {
        UUID gathering = gathering(a, a, b);
        UUID circle = circle(a, a, b);
        UUID removed = moment(a, "删除这条", MEMORY_TIME, PUBLISHED_AT, gathering, false);
        UUID kept = moment(a, "保留这条", MEMORY_TIME, PUBLISHED_AT, gathering, false);
        UUID album = album(a, "测试时光", removed, kept);
        circleShare(circle, a, "moment", removed, PUBLISHED_AT, false, b);
        circleShare(circle, a, "moment", kept, PUBLISHED_AT, false, b);
        gatheringShare(gathering, a, removed, PUBLISHED_AT, b);
        gatheringShare(gathering, a, kept, PUBLISHED_AT, b);

        expectActiveReferences(removed, album, gathering, circle, 1);
        success(request(a, delete("/moments/{id}", removed)));
        assertAll(
                () -> ids(yours(a), "id", kept),
                () -> error(detail(a, removed), 404, "MOMENT_NOT_FOUND"),
                () -> success(request(a, get("/albums/{id}", album))).andExpect(jsonPath("$.data.momentCount").value(1)),
                () -> ids(success(request(a, get("/albums/{id}/moments", album))), "id", kept),
                () -> ids(success(request(b, get("/gatherings/{id}/moments", gathering))), "id", kept),
                () -> ids(success(request(b, get("/circles/{id}/shares", circle))), "objectId", kept),
                () -> ids(success(request(b, get("/moments/related"))), "momentSummary.id", kept),
                () -> expectActiveReferences(removed, album, gathering, circle, 0),
                () -> expectActiveReferences(kept, album, gathering, circle, 1)
        );
    }

    /**
     * 圈子成员有权查看瞬间但并非作者时，删除该瞬间，失败
     */
    @Test
    void shouldKeepMomentWhenReaderAttemptsDeletion() throws Exception {
        UUID circle = circle(a, a, b);
        UUID moment = moment(a, "仍然可见");
        circleShare(circle, a, "moment", moment, PUBLISHED_AT, false, b);

        error(request(b, delete("/moments/{id}", moment)), 403, "FORBIDDEN");
    }

    /**
     * 作者已删除该瞬间时，重试删除同一瞬间，成功
     */
    @Test
    void shouldLeaveStateUnchangedWhenAuthorRetriesDeletion() throws Exception {
        UUID removed = moment(a, "删除这条");
        UUID kept = moment(a, "保留这条");

        success(request(a, delete("/moments/{id}", removed)));
        success(request(a, delete("/moments/{id}", removed)));
        ids(yours(a), "id", kept);
    }

    /**
     * 作者未登录时，删除自己的瞬间，失败
     */
    @Test
    void shouldRejectDeletionWhenUserIsUnauthenticated() throws Exception {
        UUID moment = moment(a, "保留原文");

        error(request(null, delete("/moments/{id}", moment)), 401, "UNAUTHORIZED");
    }

    /**
     * 【辅助方法】：检查持久化的三类引用是否有效，即使列表已过滤掉原瞬间也检查引用本身。
     */
    private void expectActiveReferences(UUID moment, UUID album, UUID gathering, UUID circle, int expected) {
        String momentId = moment.toString();
        assertAll("瞬间 " + momentId + " 的有效引用",
                () -> assertEquals(expected, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM album_moment
                        WHERE album_id = UUID_TO_BIN(?) AND moment_id = UUID_TO_BIN(?) AND deleted_at IS NULL
                        """, Integer.class, album.toString(), momentId), "时光引用数量"),
                () -> assertEquals(expected, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM moment
                        WHERE gathering_id = UUID_TO_BIN(?) AND id = UUID_TO_BIN(?)
                        """, Integer.class, gathering.toString(), momentId), "同行引用数量"),
                () -> assertEquals(expected, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM circle_share
                        WHERE circle_id = UUID_TO_BIN(?) AND object_id = UUID_TO_BIN(?)
                          AND object_type = 'moment' AND deleted_at IS NULL
                        """, Integer.class, circle.toString(), momentId), "圈子引用数量")
        );
    }
}

package com.you.youbackend.moment;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** MOMENT-BROWSE-RELATED 浏览“与你有关”信息流 */
class BrowseRelatedCustomerTest extends MomentCustomerTestSupport {
    /**
     * 用户同时收到瞬间和时光分享时，浏览两种内容，成功
     */
    @Test
    void shouldListReceivedMomentsAndAlbumsWhenBothAreShared() throws Exception {
        UUID gathering = gathering(b, a, b);
        UUID firstCircle = circle(c, a, c);
        UUID secondCircle = circle(c, c, d);
        UUID sharedMoment = moment(b, "一起看展", MEMORY_TIME, PUBLISHED_AT, gathering, false);
        UUID albumMoment = moment(c, "时光里的照片");
        UUID unrelated = moment(c, "只分享给 D");
        moment(a, "自己的私人记录");
        UUID album = album(c, "旅行相册", albumMoment);
        gatheringShare(gathering, b, sharedMoment, MEMORY_TIME, a);
        circleShare(firstCircle, c, "album", album, PUBLISHED_AT, false, a);
        circleShare(secondCircle, c, "moment", unrelated, "2026-11-22T10:00:00Z", false, d);

        var feed = related(a).andExpectAll(
                jsonPath("$.data.items", hasSize(2)),
                jsonPath("$.data.items[0].objectType").value("album"),
                jsonPath("$.data.items[0].albumSummary.id").value(album.toString()),
                jsonPath("$.data.items[0].albumSummary.name").value("旅行相册"),
                jsonPath("$.data.items[0].albumSummary.momentCount").value(1),
                jsonPath("$.data.items[0].actor.id").value(c.toString()),
                jsonPath("$.data.items[1].objectType").value("moment"),
                jsonPath("$.data.items[1].momentSummary.id").value(sharedMoment.toString()),
                jsonPath("$.data.items[1].momentSummary.bodyPreview").value("一起看展"),
                jsonPath("$.data.items[1].actor.id").value(b.toString()));
        endOfFeed(feed);
    }

    /**
     * 同一瞬间收到多条分享且最新一条已撤回时，浏览剩余有效分享中最新的一条，成功
     */
    @Test
    void shouldUseLatestValidShareWhenSameMomentWasReceivedMoreThanOnce() throws Exception {
        UUID gathering = gathering(b, a, b);
        UUID firstCircle = circle(b, a, b);
        UUID secondCircle = circle(b, a, b);
        UUID moment = moment(b, "一起看展", MEMORY_TIME, PUBLISHED_AT, gathering, false);
        gatheringShare(gathering, b, moment, MEMORY_TIME, a);
        circleShare(firstCircle, b, "moment", moment, PUBLISHED_AT, false, a);
        circleShare(secondCircle, b, "moment", moment, "2026-11-22T10:00:00Z", true, a);

        var feed = related(a);
        ids(feed, "momentSummary.id", moment);
        feed.andExpectAll(jsonPath("$.data.items[0].objectType").value("moment"),
                jsonPath("$.data.items[0].actor.id").value(b.toString()));
        assertEquals(LocalDateTime.of(2026, 11, 21, 10, 0),
                LocalDateTime.parse(data(feed).path("items").path(0).path("sharedAt").asString()));
        endOfFeed(feed);
    }

    /**
     * 分享时间相同且原瞬间的记忆时间顺序不同时，按分享顺序跨页浏览与你有关的信息流，成功
     */
    @Test
    void shouldContinueByShareOrderWhenSharingTimesAreEqual() throws Exception {
        UUID circle = circle(b, a, b);
        UUID earliest = moment(b, "较早分享");
        UUID smaller = moment(b, "较小分享 ID", "2026-11-19T10:00:00Z", PUBLISHED_AT, null, false);
        UUID larger = moment(b, "较大分享 ID", "2026-11-18T10:00:00Z", PUBLISHED_AT, null, false);
        String tiedShareTime = "2026-11-22T10:00:00Z";
        circleShare(circle, b, "moment", earliest, PUBLISHED_AT, false, a);
        circleShare(circle, b, "moment", smaller, tiedShareTime, false, a);
        circleShare(circle, b, "moment", larger, tiedShareTime, false, a);

        var first = success(request(a, get("/moments/related").param("limit", "1")));
        ids(first, "momentSummary.id", larger);
        first.andExpectAll(jsonPath("$.data.hasMore").value(true), jsonPath("$.data.nextCursor").isMap());
        var cursor = data(first).path("nextCursor");
        var second = success(request(a, get("/moments/related").param("limit", "2")
                .param("cursor.sharedAt", cursor.path("sharedAt").asString())
                .param("cursor.id", cursor.path("id").asString())));
        ids(second, "momentSummary.id", smaller, earliest);
        endOfFeed(second);
    }

    /**
     * 用户退出一个分享圈子且另一条分享的瞬间已被作者删除时，刷新与你有关的信息流并获取空列表，成功
     */
    @Test
    void shouldReturnEmptyPageWhenPreviouslyReceivedContentBecomesUnavailable() throws Exception {
        UUID firstCircle = circle(b, a, b);
        UUID secondCircle = circle(b, a, b);
        UUID hiddenAfterLeaving = moment(b, "退出后不可见");
        UUID hiddenAfterDeletion = moment(b, "删除后不可见", PUBLISHED_AT, PUBLISHED_AT, null, false);
        circleShare(firstCircle, b, "moment", hiddenAfterLeaving, PUBLISHED_AT, false, a);
        circleShare(secondCircle, b, "moment", hiddenAfterDeletion, "2026-11-22T10:00:00Z", false, a);

        ids(related(a), "momentSummary.id", hiddenAfterDeletion, hiddenAfterLeaving);
        leaveCircle(a, firstCircle);
        success(request(b, delete("/moments/{id}", hiddenAfterDeletion)));
        var after = related(a);
        ids(after, "momentSummary.id");
        endOfFeed(after);
    }

    /**
     * 用户未登录时，浏览与你有关的信息流，失败
     */
    @Test
    void shouldRejectBrowsingRelatedWhenUserIsUnauthenticated() throws Exception {
        error(request(null, get("/moments/related")), 401, "UNAUTHORIZED");
    }

    /**
     * 【辅助方法】：读取当前账号的与你有关信息流。
     */
    private ResultActions related(UUID user) throws Exception {
        return success(request(user, get("/moments/related")));
    }

    /**
     * 【辅助方法】：检查信息流已经到达末页。
     */
    private void endOfFeed(ResultActions response) throws Exception {
        response.andExpectAll(jsonPath("$.data.hasMore").value(false), jsonPath("$.data.nextCursor").doesNotExist());
    }
}

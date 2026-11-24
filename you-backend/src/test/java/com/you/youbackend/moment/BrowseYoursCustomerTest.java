package com.you.youbackend.moment;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** MOMENT-BROWSE-YOURS 浏览自己的瞬间流 */
class BrowseYoursCustomerTest extends MomentCustomerTestSupport {
    /**
     * 浏览自己的瞬间流，成功
     */
    @Test
    void shouldListOnlyOwnActiveMomentsWhenBrowsingYours() throws Exception {
        UUID circle = circle(a, a, b);
        UUID privateMoment = moment(a, "私人记录");
        UUID shared = moment(a, "我的分享", "2026-11-21T10:00:00Z", PUBLISHED_AT, null, false);
        moment(a, "已删除", MEMORY_TIME, PUBLISHED_AT, null, true);
        circleShare(circle, a, "moment", shared, PUBLISHED_AT, false, b);

        var feed = yours(a);
        ids(feed, "id", shared, privateMoment);
        feed.andExpectAll(jsonPath("$.data.hasMore").value(false), jsonPath("$.data.nextCursor").doesNotExist());
    }

    /**
     * 本人纯文字瞬间没有标题和图片时，读取该瞬间的卡片摘要，成功
     */
    @Test
    void shouldShowOnlyExistingSummaryContentWhenMomentHasNoTitleOrImage() throws Exception {
        UUID moment = moment(a, "今天很开心");
        describeMoment(moment, null, "HAPPY", null);

        yours(a).andExpectAll(
                jsonPath("$.data.items", hasSize(1)),
                jsonPath("$.data.items[0].id").value(moment.toString()),
                jsonPath("$.data.items[0].title").doesNotExist(),
                jsonPath("$.data.items[0].bodyPreview").value("今天很开心"),
                jsonPath("$.data.items[0].imageCount").value(0),
                jsonPath("$.data.items[0].mood").value("HAPPY"));
    }

    /**
     * 记忆时间相同的瞬间分布在不同页时，按游标继续浏览自己的瞬间流，成功
     */
    @Test
    void shouldContinueWithoutDuplicatesWhenMemoryTimesAreEqual() throws Exception {
        UUID olderMemory = moment(a, "较晚发布", "2026-11-19T10:00:00Z", "2026-11-22T10:00:00Z", null, false);
        UUID smaller = moment(a, "较小 ID", MEMORY_TIME, "2026-11-20T12:00:00Z", null, false);
        UUID larger = moment(a, "较大 ID", MEMORY_TIME, "2026-11-20T12:00:00Z", null, false);

        var first = success(request(a, get("/moments/yours").param("limit", "1")));
        ids(first, "id", larger);
        first.andExpectAll(jsonPath("$.data.hasMore").value(true), jsonPath("$.data.nextCursor").isMap());

        var cursor = data(first).path("nextCursor");
        var second = success(request(a, get("/moments/yours").param("limit", "2")
                .param("cursor.memoryTime", cursor.path("memoryTime").asString())
                .param("cursor.id", cursor.path("id").asString())));
        ids(second, "id", smaller, olderMemory);
        second.andExpectAll(jsonPath("$.data.hasMore").value(false), jsonPath("$.data.nextCursor").doesNotExist());
    }

    /**
     * 本人没有有效瞬间时，浏览自己的瞬间流并获取空列表，成功
     */
    @Test
    void shouldReturnEmptyPageWhenUserHasNoActiveMoments() throws Exception {
        moment(a, "已删除", MEMORY_TIME, PUBLISHED_AT, null, true);

        var feed = yours(a);
        ids(feed, "id");
        feed.andExpectAll(jsonPath("$.data.hasMore").value(false), jsonPath("$.data.nextCursor").doesNotExist());
    }

    /**
     * 用户未登录时，浏览自己的瞬间流，失败
     */
    @Test
    void shouldRejectBrowsingYoursWhenUserIsUnauthenticated() throws Exception {
        error(request(null, get("/moments/yours")), 401, "UNAUTHORIZED");
    }
}

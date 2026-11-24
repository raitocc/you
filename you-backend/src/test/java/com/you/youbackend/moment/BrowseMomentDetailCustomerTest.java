package com.you.youbackend.moment;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MOMENT-BROWSE-DETAIL 查看瞬间详情 */
class BrowseMomentDetailCustomerTest extends MomentCustomerTestSupport {
    /**
     * 作者的瞬间包含图文和关联时，查看完整详情及关联信息，成功
     */
    @Test
    void shouldShowFullDetailAndAssociationsWhenViewerIsAuthor() throws Exception {
        UUID gathering = gathering(a, a, b);
        UUID circle = circle(a, a, b);
        String body = "沿着江边散步，停下来拍了晚霞和远处的桥。";
        UUID moment = moment(a, body, MEMORY_TIME, PUBLISHED_AT, gathering, false);
        UUID first = image(a);
        UUID second = image(a);
        describeMoment(moment, "江边", "HAPPY", "江边步道");
        images(moment, second, first);
        tags(moment, "散步", "晚霞");
        circleShare(circle, a, "moment", moment, PUBLISHED_AT, false, b);

        success(detail(a, moment)).andExpectAll(
                jsonPath("$.data.id").value(moment.toString()),
                jsonPath("$.data.author.id").value(a.toString()),
                jsonPath("$.data.title").value("江边"),
                jsonPath("$.data.body").value(body),
                jsonPath("$.data.images[*].fileId", contains(second.toString(), first.toString())),
                jsonPath("$.data.tags", containsInAnyOrder("散步", "晚霞")),
                jsonPath("$.data.mood").value("HAPPY"),
                jsonPath("$.data.location.name").value("江边步道"),
                jsonPath("$.data.memoryTime").value(MEMORY_TIME),
                jsonPath("$.data.sources[*].id", containsInAnyOrder(gathering.toString(), circle.toString())),
                jsonPath("$.data.canEdit").value(true),
                jsonPath("$.data.canDelete").value(true),
                jsonPath("$.data.canShare").value(true));
    }

    /**
     * 读者是同行成员且并非作者时，通过同行入口查看瞬间内容，成功
     */
    @Test
    void shouldShowContentWithoutAuthorPermissionsWhenReaderIsGatheringMember() throws Exception {
        UUID gathering = gathering(a, a, b);
        UUID circle = circle(a, a);
        UUID moment = moment(a, "一起看展", MEMORY_TIME, PUBLISHED_AT, gathering, false);
        circleShare(circle, a, "moment", moment, PUBLISHED_AT, false, null);

        var response = success(gatheringDetail(b, gathering, moment)).andExpectAll(
                jsonPath("$.data.id").value(moment.toString()),
                jsonPath("$.data.author.id").value(a.toString()),
                jsonPath("$.data.body").value("一起看展"),
                jsonPath("$.data.gatheringId").doesNotExist());
        var detail = data(response);
        assertNoVisibleAssociations(detail.path("sources"));
        assertNoVisibleAssociations(detail.path("circleIds"));
        assertFalse(detail.path("canEdit").asBoolean(false));
        assertFalse(detail.path("canDelete").asBoolean(false));
        assertFalse(detail.path("canShare").asBoolean(false));
    }

    /**
     * 瞬间已有效分享给读者所在圈子时，通过圈子入口查看原瞬间的正文和图片，成功
     */
    @Test
    void shouldShowOriginalDetailWhenMomentIsSharedToReadersCircle() throws Exception {
        UUID circle = circle(a, a, b);
        UUID moment = moment(a, "海边日落");
        UUID image = image(a);
        images(moment, image);
        UUID share = circleShare(circle, a, "moment", moment, PUBLISHED_AT, false, b);

        success(circleDetail(b, circle, share)).andExpectAll(
                jsonPath("$.data.id").value(moment.toString()),
                jsonPath("$.data.author.id").value(a.toString()),
                jsonPath("$.data.body").value("海边日落"),
                jsonPath("$.data.images[*].fileId", contains(image.toString())));
    }

    /**
     * 非作者没有同行或圈子访问资格时，按瞬间标识打开详情，失败
     */
    @Test
    void shouldDenyDetailWhenViewerHasNoAccess() throws Exception {
        UUID moment = moment(a, "私人记录");
        images(moment, image(a));

        expectDetailDenied(detail(b, moment));
    }

    /**
     * 读者此前能查看瞬间但已退出圈子时，通过原圈子入口再次打开瞬间，失败
     */
    @Test
    void shouldDenyPreviouslyVisibleDetailWhenReaderLeavesCircle() throws Exception {
        UUID circle = circle(a, a, b);
        UUID moment = moment(a, "原来可见的记录");
        UUID share = circleShare(circle, a, "moment", moment, PUBLISHED_AT, false, b);

        success(circleDetail(b, circle, share)).andExpectAll(
                jsonPath("$.data.id").value(moment.toString()),
                jsonPath("$.data.body").value("原来可见的记录"));
        leaveCircle(b, circle);
        expectDetailDenied(circleDetail(b, circle, share));
    }

    /**
     * 瞬间已被作者删除时，作者打开该瞬间详情，失败
     */
    @Test
    void shouldDenyDetailWhenMomentIsDeleted() throws Exception {
        UUID moment = moment(a, "已删除的记录", MEMORY_TIME, PUBLISHED_AT, null, true);

        error(detail(a, moment), 404, "MOMENT_NOT_FOUND");
    }

    /**
     * 作者未登录时，查看自己的瞬间详情，失败
     */
    @Test
    void shouldRejectDetailWhenUserIsUnauthenticated() throws Exception {
        UUID moment = moment(a, "私人记录");

        error(detail(null, moment), 401, "UNAUTHORIZED");
    }

    /**
     * 【辅助方法】：检查拒绝访问的响应未携带瞬间正文或图片。
     */
    private void expectDetailDenied(ResultActions response) throws Exception {
        response.andExpectAll(status().is(anyOf(is(403), is(404))), jsonPath("$.data").doesNotExist());
    }

    /**
     * 【辅助方法】：检查该入口没有返回任何关联对象。
     */
    private void assertNoVisibleAssociations(JsonNode associations) {
        assertTrue(associations.isMissingNode() || associations.isNull()
                || associations.isArray() && associations.isEmpty());
    }
}

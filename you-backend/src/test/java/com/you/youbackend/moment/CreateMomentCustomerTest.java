package com.you.youbackend.moment;

import com.you.youbackend.gathering.model.GatheringStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MOMENT-CREATE 发布瞬间 */
class CreateMomentCustomerTest extends MomentCustomerTestSupport {
    private final UUID moment = newId();

    /**
     * 用户已登录时，发布纯文字瞬间，成功
     */
    @Test
    void shouldCreateTextOnlyMomentWhenContentIsProvided() throws Exception {
        expectPublished(publish(a, publication("今天很开心")))
                .andExpectAll(jsonPath("$.data.body").value("今天很开心"),
                        jsonPath("$.data.images").isEmpty());
    }

    /**
     * 正文空白、但存在有效图片时，发布图片瞬间，成功
     */
    @Test
    void shouldCreateImageOnlyMomentWhenContentIsBlank() throws Exception {
        UUID image = image(a);
        ObjectNode request = publication("  ");
        request.putArray("imageFileIds").add(image.toString());

        expectPublished(publish(a, request))
                .andExpectAll(jsonPath("$.data.body").value(""),
                        jsonPath("$.data.images[*].fileId", contains(image.toString())));
    }

    /**
     * 未登录时，发布瞬间，失败
     */
    @Test
    void shouldRejectCreationWhenUserIsUnauthenticated() throws Exception {
        publish(null, publication("今天很开心"))
                .andExpectAll(status().isUnauthorized(), jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /**
     * 瞬间只有标题且正文为空白时，发布瞬间，失败
     */
    @Test
    void shouldRequireContentOrImageWhenOnlyTitleIsProvided() throws Exception {
        ObjectNode request = publication("  ").put("title", "周末");
        request.putArray("imageFileIds");

        publish(a, request)
                .andExpectAll(status().isBadRequest(), jsonPath("$.code").value("INVALID_ARGUMENT"));
        yours(a).andExpect(jsonPath("$.data.items").isEmpty());
    }

    /**
     * 用户已登录时，发布完整瞬间，成功
     */
    @Test
    void shouldSaveOptionalFieldsWhenTheyAreProvided() throws Exception {
        ObjectNode request = publication("看到了晚霞").put("title", "江边").put("mood", "HAPPY")
                .put("memoryTime", "2026-11-20T18:00:00+08:00");
        request.putArray("tags").add("散步").add("晚霞");
        request.putObject("location").put("name", "江边步道");

        expectOptionalFields(expectPublished(publish(a, request)));
        expectOptionalFields(success(detail(a, moment)));
    }

    /**
     * 关联一个有效同行并分享到多个本人所在的圈子时，发布瞬间，成功
     */
    @Test
    void shouldPublishWithAssociationsWhenTargetsAreAvailable() throws Exception {
        UUID gathering = gathering(a, a, b);
        UUID firstCircle = circle(a, a, b);
        UUID secondCircle = circle(a, a, c);
        ObjectNode request = publication("一起看展").put("gatheringId", gathering.toString());
        request.putArray("circleIds").add(firstCircle.toString()).add(secondCircle.toString());

        expectPublished(publish(a, request));

        assertAll(
                () -> success(request(a, get("/gatherings/{id}/moments", gathering)))
                        .andExpect(jsonPath("$.data.items[*].id", contains(moment.toString()))),
                () -> browseCircleShares(firstCircle, b)
                        .andExpect(jsonPath("$.data.items[*].objectId", contains(moment.toString()))),
                () -> browseCircleShares(secondCircle, c)
                        .andExpect(jsonPath("$.data.items[*].objectId", contains(moment.toString())))
        );
    }

    /**
     * 关联同行失效时，发布瞬间，失败
     */
    @Test
    void shouldRejectCreationWhenGatheringIsUnavailable() throws Exception {
        UUID gathering = gathering(a, GatheringStatus.ENDED, a);
        ObjectNode request = publication("一起看展").put("gatheringId", gathering.toString());

        publish(a, request)
                .andExpectAll(status().isBadRequest(), jsonPath("$.code").value("GATHERING_ENDED"));
        yours(a).andExpect(jsonPath("$.data.items").isEmpty());
    }

    /**
     * 分享到多个目标圈子中有一个失效时，发布瞬间，失败
     */
    @Test
    void shouldRejectCreationWhenAnySelectedCircleIsUnavailable() throws Exception {
        UUID firstCircle = circle(a, a, b);
        UUID secondCircle = circle(b, b);
        ObjectNode request = publication("一起看展");
        request.putArray("circleIds").add(firstCircle.toString()).add(secondCircle.toString());

        ResultActions result = publish(a, request);

        assertAll(
                () -> result.andExpect(status().is4xxClientError()),
                () -> yours(a).andExpect(jsonPath("$.data.items").isEmpty()),
                () -> browseCircleShares(firstCircle, b)
                        .andExpect(jsonPath("$.data.items").isEmpty())
        );
    }

    /**
     * 【辅助方法】：构造包含指定正文的动态发布请求体（固定 id 与记忆时间）
     */
    private ObjectNode publication(String body) {
        return json.createObjectNode().put("id", moment.toString()).put("body", body)
                .put("memoryTime", MEMORY_TIME);
    }

    /**
     * 【辅助方法】：以指定用户身份调用发布动态接口并返回请求结果
     */
    private ResultActions publish(UUID user, ObjectNode request) throws Exception {
        return request(user, post("/moments"), request);
    }

    /**
     * 【辅助方法】：以指定查看者身份查询圈子分享列表并断言请求成功
     */
    private ResultActions browseCircleShares(UUID circle, UUID viewer) throws Exception {
        return success(request(viewer, get("/circles/{id}/shares", circle)));
    }

    /**
     * 【辅助方法】：断言动态发布成功并校验返回状态码、动态 ID 与作者 ID
     */
    private ResultActions expectPublished(ResultActions result) throws Exception {
        return result.andExpectAll(status().isCreated(), jsonPath("$.code").value("SUCCESS"),
                jsonPath("$.data.id").value(moment.toString()), jsonPath("$.data.author.id").value(a.toString()));
    }

    /**
     * 【辅助方法】：断言返回结果中包含预期的可选动态字段（标题、标签、心情、位置、记忆时间）
     */
    private void expectOptionalFields(ResultActions result) throws Exception {
        result.andExpectAll(
                jsonPath("$.data.title").value("江边"),
                jsonPath("$.data.tags", containsInAnyOrder("散步", "晚霞")),
                jsonPath("$.data.mood").value("HAPPY"),
                jsonPath("$.data.location.name").value("江边步道"),
                jsonPath("$.data.memoryTime").value("2026-11-20T10:00:00Z")
        );
    }
}

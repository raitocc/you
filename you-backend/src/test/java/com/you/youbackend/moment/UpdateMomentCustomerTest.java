package com.you.youbackend.moment;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** MOMENT-UPDATE 编辑瞬间 */
class UpdateMomentCustomerTest extends MomentCustomerTestSupport {
    /**
     * 作者已发布瞬间时，修改瞬间内容，成功
     */
    @Test
    void shouldSaveChangesAndPreserveIdentityWhenAuthorEditsMoment() throws Exception {
        UUID first = image(a);
        UUID second = image(a);
        UUID third = image(a);
        UUID moment = moment(a, "看了晚霞");
        describeMoment(moment, "江边", null, null);
        images(moment, first, second);
        tags(moment, "散步", "晚霞");
        ObjectNode changes = editBody("走到江边看夜景", third, first);
        changes.put("title", "江边夜色").put("mood", "CALM").put("memoryTime", "2026-11-20T20:00:00+08:00");
        changes.putObject("location").put("name", "江边步道");
        changes.withArray("tags").add("夜景");

        success(request(a, put("/moments/{id}", moment), changes)).andExpectAll(
                jsonPath("$.data.id").value(moment.toString()), jsonPath("$.data.author.id").value(a.toString()));
        var saved = success(detail(a, moment)).andExpectAll(
                jsonPath("$.data.id").value(moment.toString()),
                jsonPath("$.data.author.id").value(a.toString()),
                jsonPath("$.data.title").value("江边夜色"),
                jsonPath("$.data.body").value("走到江边看夜景"),
                jsonPath("$.data.images[*].fileId", contains(third.toString(), first.toString())),
                jsonPath("$.data.tags", contains("夜景")),
                jsonPath("$.data.mood").value("CALM"),
                jsonPath("$.data.location.name").value("江边步道"),
                jsonPath("$.data.memoryTime").value("2026-11-20T12:00:00Z"),
                jsonPath("$.data.createdAt").value(PUBLISHED_AT));
        assertTrue(Instant.parse(data(saved).path("updatedAt").asString()).isAfter(Instant.parse(PUBLISHED_AT)));
    }

    /**
     * 同行成员有权查看瞬间但并非作者时，编辑该瞬间，失败
     */
    @Test
    void shouldRejectUpdateWhenReaderIsNotAuthor() throws Exception {
        UUID gathering = gathering(a, a, b);
        UUID moment = moment(a, "原正文", MEMORY_TIME, PUBLISHED_AT, gathering, false);

        error(request(b, put("/moments/{id}", moment), editBody("别人改写的正文")), 403, "FORBIDDEN");
    }

    /**
     * 作者提交的替换图片不可用时，保存瞬间修改，失败
     */
    @Test
    void shouldKeepPriorContentWhenReplacementImageIsUnavailable() throws Exception {
        UUID moment = moment(a, "原正文");
        UUID image = image(a);
        images(moment, image);
        UUID unavailable = newId();

        error(request(a, put("/moments/{id}", moment), editBody("修改后的正文", unavailable)), 400, "FILE_NOT_USABLE");
        success(detail(a, moment)).andExpectAll(
                jsonPath("$.data.id").value(moment.toString()),
                jsonPath("$.data.body").value("原正文"),
                jsonPath("$.data.images[*].fileId", contains(image.toString())));
    }

    /**
     * 瞬间已被作者删除时，作者再次编辑该瞬间，失败
     */
    @Test
    void shouldRejectUpdateWhenMomentIsDeleted() throws Exception {
        UUID moment = moment(a, "已删除正文", MEMORY_TIME, PUBLISHED_AT, null, true);

        error(request(a, put("/moments/{id}", moment), editBody("尝试恢复")), 404, "MOMENT_NOT_FOUND");
    }

    /**
     * 作者未登录时，编辑自己的瞬间，失败
     */
    @Test
    void shouldRejectUpdateWhenUserIsUnauthenticated() throws Exception {
        UUID moment = moment(a, "原正文");

        error(request(null, put("/moments/{id}", moment), editBody("尝试修改")), 401, "UNAUTHORIZED");
    }

    /**
     * 【辅助方法】：准备当前编辑入口要求的完整请求。
     */
    private ObjectNode editBody(String content, UUID... images) {
        ObjectNode body = json.createObjectNode().put("body", content).put("memoryTime", MEMORY_TIME);
        body.putNull("title").putNull("mood").putNull("location");
        var files = body.putArray("imageFileIds");
        for (UUID image : images) {
            files.add(image.toString());
        }
        body.putArray("tags");
        return body;
    }
}

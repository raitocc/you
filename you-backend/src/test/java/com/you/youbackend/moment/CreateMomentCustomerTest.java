package com.you.youbackend.moment;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.gathering.model.GatheringStatus;
import com.you.youbackend.user.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MOMENT-CREATE-001 发布瞬间 */
@Testcontainers
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
class CreateMomentCustomerTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private JwtService jwt;

    // 阻止启动时向对象存储写入通知配置。图片 URL 在本地签名。
    @MockitoBean private S3Client s3Client;

    private final UUID author = UuidCreator.getTimeOrderedEpoch();
    private final UUID firstReader = UuidCreator.getTimeOrderedEpoch();
    private final UUID secondReader = UuidCreator.getTimeOrderedEpoch();
    private final UUID moment = UuidCreator.getTimeOrderedEpoch();
    private final UUID image = UuidCreator.getTimeOrderedEpoch();
    private final UUID gathering = UuidCreator.getTimeOrderedEpoch();
    private final UUID firstCircle = UuidCreator.getTimeOrderedEpoch();
    private final UUID secondCircle = UuidCreator.getTimeOrderedEpoch();

    /**
     * 【在每个测试之前】：插入默认作者测试用户
     */
    @BeforeEach
    void createAuthor() {
        insertUser(author);
    }

    /**
     * 【在每个测试之后】：清理测试夹具与动态发布产生的数据库测试数据
     */
    @AfterEach
    void removeFixturesAndPublishedData() {
        jdbc.update("DELETE FROM user_inbox WHERE object_type = 'moment' AND object_id = ?", bytes(moment));
        jdbc.update("DELETE FROM circle_share WHERE object_type = 'moment' AND object_id = ?", bytes(moment));
        jdbc.update("DELETE FROM moment_image WHERE moment_id = ?", bytes(moment));
        jdbc.update("DELETE FROM moment_tag WHERE moment_id = ?", bytes(moment));
        jdbc.update("DELETE FROM moment WHERE id = ?", bytes(moment));
        jdbc.update("DELETE FROM media_file WHERE id = ?", bytes(image));
        jdbc.update("DELETE FROM gathering_member WHERE gathering_id = ?", bytes(gathering));
        jdbc.update("DELETE FROM gathering WHERE id = ?", bytes(gathering));
        for (UUID circle : List.of(firstCircle, secondCircle)) {
            jdbc.update("DELETE FROM circle_member WHERE circle_id = ?", bytes(circle));
            jdbc.update("DELETE FROM circle WHERE id = ?", bytes(circle));
        }
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?)",
                bytes(author), bytes(firstReader), bytes(secondReader));
    }

    /**
     * 用户已登录时，发布纯文字瞬间，成功
     */
    @Test
    void shouldCreateTextOnlyMomentWhenContentIsProvided() throws Exception {
        expectPublished(publish(author, publication("今天很开心")))
                .andExpectAll(jsonPath("$.data.body").value("今天很开心"),
                        jsonPath("$.data.images").isEmpty());
    }

    /**
     * 正文空白、但存在有效图片时，发布图片瞬间，成功
     */
    @Test
    void shouldCreateImageOnlyMomentWhenContentIsBlank() throws Exception {
        jdbc.update("""
                INSERT INTO media_file (id, owner_user_id, storage_key, mime_type, byte_size, created_at, updated_at)
                VALUES (?, ?, ?, 'image/jpeg', 12, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bytes(image), bytes(author), "customer-test/" + image + ".jpg");
        ObjectNode request = publication("  ");
        request.putArray("imageFileIds").add(image.toString());

        expectPublished(publish(author, request))
                .andExpectAll(jsonPath("$.data.body").value(""),
                        jsonPath("$.data.images[*].fileId", contains(image.toString())));
    }

    /**
     * 未登录时，发布瞬间，失败
     */
    @Test
    void shouldRejectCreationWhenUserIsUnauthenticated() throws Exception {
        mvc.perform(post("/moments").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(publication("今天很开心"))))
                .andExpectAll(status().isUnauthorized(), jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /**
     * 瞬间只有标题且正文为空白时，发布瞬间，失败
     */
    @Test
    void shouldRequireContentOrImageWhenOnlyTitleIsProvided() throws Exception {
        ObjectNode request = publication("  ").put("title", "周末");
        request.putArray("imageFileIds");

        publish(author, request)
                .andExpectAll(status().isBadRequest(), jsonPath("$.code").value("INVALID_ARGUMENT"));
        browseYours().andExpect(jsonPath("$.data.items").isEmpty());
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

        expectOptionalFields(expectPublished(publish(author, request)));
        expectOptionalFields(mvc.perform(authenticated(get("/moments/{id}", moment), author))
                .andExpectAll(status().isOk(), jsonPath("$.code").value("SUCCESS")));
    }

    /**
     * 关联一个有效同行并分享到多个本人所在的圈子时，发布瞬间，成功
     */
    @Test
    void shouldPublishWithAssociationsWhenTargetsAreAvailable() throws Exception {
        insertUser(firstReader);
        insertUser(secondReader);
        insertGathering(GatheringStatus.ONGOING, List.of(author, firstReader));
        insertCircle(firstCircle, author, List.of(author, firstReader));
        insertCircle(secondCircle, author, List.of(author, secondReader));
        ObjectNode request = publication("一起看展").put("gatheringId", gathering.toString());
        request.putArray("circleIds").add(firstCircle.toString()).add(secondCircle.toString());

        expectPublished(publish(author, request));

        assertAll(
                () -> mvc.perform(authenticated(get("/gatherings/{id}/moments", gathering), author))
                        .andExpectAll(status().isOk(), jsonPath("$.code").value("SUCCESS"),
                                jsonPath("$.data.items[*].id", contains(moment.toString()))),
                () -> browseCircleShares(firstCircle, firstReader)
                        .andExpect(jsonPath("$.data.items[*].objectId", contains(moment.toString()))),
                () -> browseCircleShares(secondCircle, secondReader)
                        .andExpect(jsonPath("$.data.items[*].objectId", contains(moment.toString())))
        );
    }

    /**
     * 关联同行失效时，发布瞬间，失败
     */
    @Test
    void shouldRejectCreationWhenGatheringIsUnavailable() throws Exception {
        insertGathering(GatheringStatus.ENDED, List.of(author));
        ObjectNode request = publication("一起看展").put("gatheringId", gathering.toString());

        publish(author, request)
                .andExpectAll(status().isBadRequest(), jsonPath("$.code").value("GATHERING_ENDED"));
        browseYours().andExpect(jsonPath("$.data.items").isEmpty());
    }

    /**
     * 分享到多个目标圈子中有一个失效时，发布瞬间，失败
     */
    @Test
    void shouldRejectCreationWhenAnySelectedCircleIsUnavailable() throws Exception {
        insertUser(firstReader);
        insertCircle(firstCircle, author, List.of(author, firstReader));
        insertCircle(secondCircle, firstReader, List.of(firstReader));
        ObjectNode request = publication("一起看展");
        request.putArray("circleIds").add(firstCircle.toString()).add(secondCircle.toString());

        ResultActions result = publish(author, request);

        assertAll(
                () -> result.andExpect(status().is4xxClientError()),
                () -> browseYours().andExpect(jsonPath("$.data.items").isEmpty()),
                () -> browseCircleShares(firstCircle, firstReader)
                        .andExpect(jsonPath("$.data.items").isEmpty())
        );
    }

    /**
     * 【辅助方法】：构造包含指定正文的动态发布请求体（固定 id 与记忆时间）
     */
    private ObjectNode publication(String body) {
        return json.createObjectNode().put("id", moment.toString()).put("body", body)
                .put("memoryTime", "2026-11-20T10:00:00Z");
    }

    /**
     * 【辅助方法】：以指定用户身份调用发布动态接口并返回请求结果
     */
    private ResultActions publish(UUID user, ObjectNode request) throws Exception {
        return mvc.perform(authenticated(post("/moments"), user)
                .contentType(APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    /**
     * 【辅助方法】：以作者身份查询个人动态列表并断言请求成功
     */
    private ResultActions browseYours() throws Exception {
        return mvc.perform(authenticated(get("/moments/yours"), author))
                .andExpectAll(status().isOk(), jsonPath("$.code").value("SUCCESS"));
    }

    /**
     * 【辅助方法】：以指定查看者身份查询圈子分享列表并断言请求成功
     */
    private ResultActions browseCircleShares(UUID circle, UUID viewer) throws Exception {
        return mvc.perform(authenticated(get("/circles/{id}/shares", circle), viewer))
                .andExpectAll(status().isOk(), jsonPath("$.code").value("SUCCESS"));
    }

    /**
     * 【辅助方法】：为测试请求加上指定用户的 Bearer JWT 鉴权头
     */
    private MockHttpServletRequestBuilder   authenticated(MockHttpServletRequestBuilder request, UUID user) {
        return request.header(AUTHORIZATION, "Bearer " + jwt.createAccessToken(user, Instant.now().plusSeconds(300)));
    }

    /**
     * 【辅助方法】：断言动态发布成功并校验返回状态码、动态 ID 与作者 ID
     */
    private ResultActions expectPublished(ResultActions result) throws Exception {
        return result.andExpectAll(status().isCreated(), jsonPath("$.code").value("SUCCESS"),
                jsonPath("$.data.id").value(moment.toString()), jsonPath("$.data.author.id").value(author.toString()));
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

    /**
     * 【辅助方法】：向数据库插入指定 ID 的测试用户记录
     */
    private void insertUser(UUID id) {
        jdbc.update("""
            INSERT INTO app_user (id, username, password_hash, nickname, created_at, updated_at)
            VALUES (?, ?, 'unused', '客户测试用户', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """, bytes(id), "customer-" + id);
    }

    /**
     * 【辅助方法】：向数据库插入指定状态和成员列表的同行活动及成员记录
     */
    private void insertGathering(GatheringStatus status, List<UUID> members) {
        jdbc.update("""
            INSERT INTO gathering (id, creator_user_id, name, status, invite_code, created_at, updated_at)
            VALUES (?, ?, '同行 G1', ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """, bytes(gathering), bytes(author), status.getCode(), inviteCode(gathering));
        for (UUID member : members) {
            jdbc.update("""
                INSERT INTO gathering_member (id, gathering_id, user_id, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bytes(UuidCreator.getTimeOrderedEpoch()), bytes(gathering), bytes(member));
        }
    }

    /**
     * 【辅助方法】：向数据库插入指定创建者和成员列表的圈子及成员记录
     */
    private void insertCircle(UUID circle, UUID owner, List<UUID> members) {
        jdbc.update("""
            INSERT INTO circle (id, creator_user_id, name, invite_code, created_at, updated_at)
            VALUES (?, ?, '测试圈子', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """, bytes(circle), bytes(owner), inviteCode(circle));
        for (UUID member : members) {
            jdbc.update("""
                INSERT INTO circle_member (id, circle_id, user_id, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bytes(UuidCreator.getTimeOrderedEpoch()), bytes(circle), bytes(member));
        }
    }

    /**
     * 【辅助方法】：根据 UUID 截取并去除连字符生成邀请码
     */
    private static String inviteCode(UUID id) {
        return id.toString().substring(19).replace("-", "");
    }

    /**
     * 【辅助方法】：将 UUID 转换为 16 字节数组以便写入数据库
     */
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }
}

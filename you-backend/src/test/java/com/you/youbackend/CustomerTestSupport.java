package com.you.youbackend;

import com.you.youbackend.user.service.JwtService;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 客户测试的隔离容器、HTTP 请求和通用响应断言。 */
@Testcontainers
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
public abstract class CustomerTestSupport {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired private MockMvc mvc;
    @Autowired protected JsonMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @MockitoBean private S3Client s3Client;

    /**
     * 【辅助方法】：将应用连接到本测试类专用的 MySQL 和 Redis。
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    /**
     * 【辅助方法】：发送请求，指定用户时签发实际的 Bearer Token。
     */
    protected ResultActions request(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        if (user != null) {
            request.header(AUTHORIZATION, "Bearer " + jwt.createAccessToken(user, Instant.now().plusSeconds(300)));
        }
        return mvc.perform(request);
    }

    /**
     * 【辅助方法】：发送带 JSON 请求体的业务请求。
     */
    protected ResultActions request(UUID user, MockHttpServletRequestBuilder request, ObjectNode body) throws Exception {
        return request(user, request.contentType(APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /**
     * 【辅助方法】：检查成功响应并返回可继续断言的结果。
     */
    protected ResultActions success(ResultActions response) throws Exception {
        return response.andExpectAll(status().isOk(), jsonPath("$.code").value("SUCCESS"));
    }

    /**
     * 【辅助方法】：检查错误状态、业务码以及没有返回业务对象。
     */
    protected void error(ResultActions response, int httpStatus, String code) throws Exception {
        response.andExpectAll(status().is(httpStatus), jsonPath("$.code").value(code), jsonPath("$.data").doesNotExist());
    }

    /**
     * 【辅助方法】：读取响应中的业务数据以检查游标或混合类型列表。
     */
    protected JsonNode data(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    /**
     * 【辅助方法】：断言列表中的对象 ID 与数量、顺序均一致。
     */
    protected void ids(ResultActions response, String field, UUID... expected) throws Exception {
        response.andExpect(jsonPath("$.data.items", hasSize(expected.length)));
        if (expected.length > 0) {
            String[] ids = Arrays.stream(expected).map(UUID::toString).toArray(String[]::new);
            response.andExpect(jsonPath("$.data.items[*]." + field, contains(ids)));
        }
    }
}

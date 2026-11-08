package com.you.youbackend.album;

import com.you.youbackend.media.service.MediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证实际 MVC 注册和生成的 OpenAPI，避免只在独立 MockMvc 中注册解析器后误判接入完成。 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class GetPageIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private MediaService media;

    @Test
    void actualMvcConfigurationBindsAndValidatesPageQueries() throws Exception {
        // 使用真实 MVC 配置和数据库，以无历史记录的用户验证首屏；不运行 JWT 过滤器。
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        UUID user = UUID.randomUUID();
        for (String path : List.of("/albums", "/moments/yours")) {
            mvc.perform(get(path).requestAttr("currentUserId", user))
                    .andExpect(status().isOk()).andExpect(jsonPath("data.items").isEmpty());
            mvc.perform(get(path).requestAttr("currentUserId", user).param("limit", "101"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void openApiListsFlattenedQueryFieldsWithoutRequestBodies() throws Exception {
        // 运行时解析与文档分别验证，三种游标均须展示展开后的查询字段。
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(body).path("paths");
        for (String path : List.of("/albums", "/moments/yours", "/albums/{albumId}/moments")) {
            JsonNode operation = paths.path(path).path("get");
            assertFalse(operation.isMissingNode(), path);
            assertFalse(operation.has("requestBody"), path);
            List<String> queries = new ArrayList<>();
            for (JsonNode parameter : operation.path("parameters")) {
                if ("query".equals(parameter.path("in").asText())) {
                    queries.add(parameter.path("name").asText());
                }
            }
            assertTrue(queries.containsAll(List.of("limit", "cursor.id")), queries.toString());
            assertTrue(queries.contains(path.equals("/albums") ? "cursor.createdAt" : "cursor.memoryTime"));
            if (path.contains("{albumId}")) {
                assertTrue(queries.contains("cursor.createdAt"));
            }
            assertFalse(queries.contains("pageRequest"));
        }
    }
}

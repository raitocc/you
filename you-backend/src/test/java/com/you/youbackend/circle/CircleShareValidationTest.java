package com.you.youbackend.circle;
import com.you.youbackend.circle.controller.CircleController;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.circle.model.*;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.common.request.*;
import com.you.youbackend.user.filter.JwtAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** 验证 HTTP 请求边界、两类游标绑定及撤回响应结构。 */
class CircleShareValidationTest {
    final CircleService service = mock(CircleService.class);
    final UUID user = UUID.randomUUID(), circle = UUID.randomUUID(), share = UUID.randomUUID();
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CircleController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PageQueryArgumentResolver(JsonMapper.builder().build())).build();
    @Test void rejectsMissingInvalidAndDuplicateTargets() throws Exception {
        for (String body : new String[]{"{}", "{\"objectType\":\"gathering\"}",
                "{\"objectType\":\"moment\",\"objectId\":\"" + share + "\",\"circleIds\":[]}",
                "{\"objectType\":\"moment\",\"objectId\":\"" + share + "\",\"circleIds\":[\"" + circle + "\",\"" + circle + "\"]}",
                "{\"objectType\":\"moment\",\"objectId\":\"" + share + "\",\"circleIds\":[null]}"}) {
            mvc.perform(post("/circles/shares").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void bindsBothCursorsAndRejectsPartialCursor() throws Exception {
        String time = "2026-11-10T06:00:00Z";
        mvc.perform(get("/circles/{id}/shares", circle).requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .param("cursor.sharedAt", time).param("cursor.id", share.toString())).andExpect(status().isOk());
        verify(service).browseShares(circle, user, new PageRequest<>(new CircleShareCursor(Instant.parse(time), share), 20));
        mvc.perform(get("/circles/{id}/shares/{share}/moments", circle, share).requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .param("cursor.memoryTime", time).param("cursor.id", share.toString())).andExpect(status().isOk());
        verify(service).browseSharedAlbumMoments(circle, share, user, new PageRequest<>(new CircleShareMomentCursor(Instant.parse(time), share), 20));
        for (String path : new String[]{"/circles/"+circle+"/shares", "/circles/"+circle+"/shares/"+share+"/moments"}) {
            mvc.perform(get(path).requestAttr(JwtAuthFilter.CURRENT_USER_ID, user).param("cursor.id", share.toString())).andExpect(status().isBadRequest());
            mvc.perform(get(path).requestAttr(JwtAuthFilter.CURRENT_USER_ID, user).param("limit", "101")).andExpect(status().isBadRequest());
        }
    }
    @Test void shareAndWithdrawReturnNullData() throws Exception {
        String body = """
                {"objectType":"moment","objectId":"%s","circleIds":["%s"]}
                """.formatted(share, circle);
        mvc.perform(post("/circles/shares").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value("SUCCESS"))
                .andExpect(jsonPath("data").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(delete("/circles/{id}/shares/{share}", circle, share).requestAttr(JwtAuthFilter.CURRENT_USER_ID, user))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value("SUCCESS")).andExpect(content().json("{\"code\":\"SUCCESS\",\"message\":\"操作成功\",\"data\":null}"));
        verify(service).withdrawShare(circle, share, user);
    }
}

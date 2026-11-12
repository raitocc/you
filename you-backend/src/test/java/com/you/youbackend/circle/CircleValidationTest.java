package com.you.youbackend.circle;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.circle.controller.CircleController;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.circle.request.*;
import com.you.youbackend.circle.model.*;
import com.you.youbackend.circle.response.CircleInvitationResponse;
import com.you.youbackend.circle.response.CircleJoinPreviewResponse;
import com.you.youbackend.circle.response.CircleListResponse;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageQueryArgumentResolver;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.user.filter.JwtAuthFilter;
import com.you.youbackend.user.response.PublicUserResponse;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC 边界验证，覆盖 Unicode、UUIDv7、必填可空字段及泛型游标绑定。 */
class CircleValidationTest {
    private final CircleService service = mock(CircleService.class);
    private final UUID user = UUID.randomUUID();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CircleController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PageQueryArgumentResolver(JsonMapper.builder().build())).build();

    @Test void invitationRequiresExplicitSwitchAndReturnsNullCodeWhenDisabled() throws Exception {
        UUID circleId = UUID.randomUUID();
        when(service.updateInvitation(circleId, new UpdateCircleInvitationRequest(false), user))
                .thenReturn(new CircleInvitationResponse(false, null));
        mvc.perform(put("/circles/{id}/invitation", circleId).requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(put("/circles/{id}/invitation", circleId).requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"inviteEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inviteEnabled").value(false))
                .andExpect(jsonPath("$.data.inviteCode").hasJsonPath())
                .andExpect(jsonPath("$.data.inviteCode").doesNotExist());
    }

    @Test void joinEndpointsValidateCodeAndPreviewSerializesIsMember() throws Exception {
        for (String route : List.of("/circles/join-preview", "/circles/join")) {
            for (String invalid : List.of("null", "\"\"", "\"   \"", "\"ABCDEFGHIJKLMNOPQ\"")) {
                mvc.perform(post(route).requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":" + invalid + "}"))
                        .andExpect(status().isBadRequest());
            }
        }
        var summary = new CircleJoinPreviewResponse.CircleSummary(UUID.randomUUID(), "家人", null,
                new PublicUserResponse(user, "成员", null),
                null, "https://example.test/default.png");
        when(service.previewJoin(new CircleJoinPreviewRequest("ABCD1234EFGH5678"), user))
                .thenReturn(new CircleJoinPreviewResponse(summary, false));
        mvc.perform(post("/circles/join-preview").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\"ABCD1234EFGH5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMember").value(false))
                .andExpect(jsonPath("$.data.circle.id").value(summary.id().toString()));
    }

    @Test void joinReturnsSuccessWithNullData() throws Exception {
        mvc.perform(post("/circles/join").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\"ABCD1234EFGH5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").hasJsonPath())
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(service).join(new JoinCircleRequest("ABCD1234EFGH5678"), user);
    }

    @Test void unicodeLengthsAndUuidVersionAreValidated() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            UUID id = UuidCreator.getTimeOrderedEpoch();
            assertTrue(validator.validate(new CreateCircleRequest(id, "  😀".strip() + "😀".repeat(127), "😀".repeat(5000), null)).isEmpty());
            assertFalse(validator.validate(new CreateCircleRequest(id, "😀".repeat(129), null, null)).isEmpty());
            assertFalse(validator.validate(new CreateCircleRequest(id, " ", null, null)).isEmpty());
            assertFalse(validator.validate(new CreateCircleRequest(id, "名称", "😀".repeat(5001), null)).isEmpty());
            assertFalse(validator.validate(new CreateCircleRequest(UUID.randomUUID(), "名称", null, null)).isEmpty());
        }
    }

    @Test void createsOnceAndReturns409ForDuplicateSubmission() throws Exception {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        doNothing().doThrow(BusinessException.conflict("RESOURCE_CONFLICT", "圈子 ID 已被占用"))
                .when(service).createCircle(any(), eq(user));
        String body = "{\"id\":\"" + id + "\",\"name\":\"  家人  \"}";
        mvc.perform(post("/circles").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").hasJsonPath()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(post("/circles").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("RESOURCE_CONFLICT"));
        for (String invalid : List.of("{}", "{\"id\":\"bad\",\"name\":\"a\"}",
                "{\"id\":\"" + id + "\",\"name\":\"a\",\"avatarFileId\":\"bad\"}")) {
            mvc.perform(post("/circles").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
    }

    @Test void updateRequiresDescriptionButAllowsNullAndOmittedAvatar() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/circles/{id}", id).requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"家人\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/circles/{id}", id).requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"家人\",\"description\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").hasJsonPath()).andExpect(jsonPath("$.data").doesNotExist());
        verify(service).updateCircle(id, new UpdateCircleRequest("家人", null, null), user);
    }

    @Test void bindsCursorAndRejectsIncompleteOrOutOfRangePaging() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/circles").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user)
                .param("cursor.joinedAt", "2026-11-10T06:00:00Z").param("cursor.id", id.toString()))
                .andExpect(status().isOk());
        verify(service).listMyCircles(user, new PageRequest<>(new CircleListCursor(Instant.parse("2026-11-10T06:00:00Z"), id), 20));
        mvc.perform(get("/circles").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user).param("cursor.id", id.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/circles").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user).param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test void listItemOnlyContainsIdNameAndAvatar() throws Exception {
        UUID circleId = UUID.randomUUID();
        when(service.listMyCircles(eq(user), any())).thenReturn(new ResultPage<>(
                List.of(new CircleListResponse(circleId, "家人", "https://example.test/avatar.png")), null, false));
        mvc.perform(get("/circles").requestAttr(JwtAuthFilter.CURRENT_USER_ID, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].length()").value(3))
                .andExpect(jsonPath("$.data.items[0].id").value(circleId.toString()))
                .andExpect(jsonPath("$.data.items[0].name").value("家人"))
                .andExpect(jsonPath("$.data.items[0].avatarUrl").value("https://example.test/avatar.png"))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }
}

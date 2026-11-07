package com.you.youbackend.moment;

import com.you.youbackend.common.validation.UUIDv7;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.moment.controller.MomentController;
import com.you.youbackend.moment.request.CreateMomentRequest;
import com.you.youbackend.moment.request.UpdateMomentRequest;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 校验注解与 MVC 绑定回归测试，使用模拟 Service，无需连接基础服务。 */
class MomentValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private final MomentService service = mock(MomentService.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID momentId = v7(Instant.parse("2020-01-01T00:00:00Z"));
    private MockMvc mvc;
    record PublishId(@UUIDv7 @NotNull UUID id) {}
    record ExistingId(@UUIDv7(checkTimestamp = false) UUID id) {}
    record StrictId(@UUIDv7(maxFutureMinutes = 1) UUID id) {}

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new MomentController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterAll
    static void closeValidator() { FACTORY.close(); }

    @Test
    void acceptsCurrentHistoricalAndAllowedFutureIds() {
        for (Instant time : new Instant[]{Instant.now(), Instant.parse("2020-01-01T00:00:00Z"), Instant.now().plusSeconds(240)}) {
            assertTrue(FACTORY.getValidator().validate(new PublishId(v7(time))).isEmpty());
        }
    }
    @Test
    void rejectsWrongVersionAndVariant() {
        assertFalse(FACTORY.getValidator().validate(new PublishId(UUID.randomUUID())).isEmpty());
        assertFalse(FACTORY.getValidator().validate(new PublishId(new UUID(momentId.getMostSignificantBits(), 0L))).isEmpty());
    }
    @Test
    void configuredTimestampLimitProducesSpecificMessage() {
        var errors = FACTORY.getValidator().validate(new StrictId(v7(Instant.now().plusSeconds(120))));
        assertEquals(1, errors.size());
        assertEquals("UUIDv7 生成时间最多允许超前服务端 1 分钟", errors.iterator().next().getMessage());
        assertTrue(FACTORY.getValidator().validate(new ExistingId(v7(Instant.now().plusSeconds(3600)))).isEmpty());
    }
    @Test
    void nullIsHandledOnlyByNotNull() {
        var errors = FACTORY.getValidator().validate(new PublishId(null));
        assertEquals(1, errors.size());
        assertEquals(NotNull.class, errors.iterator().next().getConstraintDescriptor().getAnnotation().annotationType());
    }
    @Test
    void creationBindsUuidImageIdsAndOffsetTime() throws Exception {
        mvc.perform(post("/moments").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content(json(momentId.toString(), "2026-01-01T08:00:00+08:00")))
                .andExpect(status().isCreated());
        var capture = ArgumentCaptor.forClass(CreateMomentRequest.class);
        verify(service).createMoment(capture.capture(), eq(userId));
        assertEquals(momentId, capture.getValue().id());
        assertEquals(new BigDecimal("31.2304"), capture.getValue().location().latitude());
        assertEquals("上海", capture.getValue().location().name());
        assertEquals(momentId, capture.getValue().imageFileIds().get(0));
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), capture.getValue().memoryTime());
    }
    @Test
    void updateBindsTypedFieldsAndNullableFields() throws Exception {
        mvc.perform(put("/moments/{momentId}", momentId).requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content("""
                  {"title":null,"body":"正文","imageFileIds":[],"tags":[],"mood":null,
                   "memoryTime":"2026-01-01T00:00:00Z","location":null}
                  """))
                .andExpect(status().isOk());
        var capture = ArgumentCaptor.forClass(UpdateMomentRequest.class);
        verify(service).updateMoment(eq(momentId), capture.capture(), eq(userId));
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), capture.getValue().memoryTime());
        assertTrue(capture.getValue().imageFileIds().isEmpty());
    }
    @Test
    void malformedUuidAndTimeReturnUnifiedErrors() throws Exception {
        for (String body : new String[]{json("bad-uuid", "2026-01-01T00:00:00Z"), json(momentId.toString(), "bad-time")}) {
            mvc.perform(post("/moments").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST_BODY"));
        }
        verifyNoInteractions(service);
    }
    @Test
    void invalidPublishIdsAreRejectedBeforeService() throws Exception {
        for (UUID id : new UUID[]{UUID.randomUUID(), v7(Instant.now().plusSeconds(3600))}) {
            mvc.perform(post("/moments").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(id.toString(), "2026-01-01T00:00:00Z")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
        }
        verifyNoInteractions(service);
    }
    @Test
    void pathValidationRejectsWrongVersionAndBindsUuid() throws Exception {
        mvc.perform(get("/moments/{momentId}", UUID.randomUUID()).requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(service);
        mvc.perform(get("/moments/{momentId}", momentId).requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isOk());
        verify(service).getMoment(momentId, userId);
    }
    @Test
    void sharedLocationStillReceivesNestedValidation() throws Exception {
        String body = json(momentId.toString(), "2026-01-01T00:00:00Z").replace("31.2304", "91.2304");
        mvc.perform(post("/moments").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(service);
    }

    private static UUID v7(Instant time) {
        return new UUID((time.toEpochMilli() << 16) | 0x7000L, 0x8000000000000000L);
    }
    private String json(String id, String time) {
        return """
            {"id":"%s","body":"正文","imageFileIds":["%s"],"memoryTime":"%s",
             "location":{"latitude":31.2304,"longitude":121.4737,"name":"上海","address":null}}
            """.formatted(id, momentId, time);
    }
}

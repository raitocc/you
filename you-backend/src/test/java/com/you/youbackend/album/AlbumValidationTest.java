package com.you.youbackend.album;

import com.you.youbackend.album.controller.AlbumController;
import com.you.youbackend.album.request.CreateAlbumRequest;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.user.filter.JwtAuthFilter;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 请求边界和 MVC 契约验证，字符长度按 Unicode 码点计算。 */
class AlbumValidationTest {
    private final AlbumService service = mock(AlbumService.class);
    private final UUID userId = UUID.randomUUID();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AlbumController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void validatesCharacterLimitsAfterTrimmingName() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            String emoji = "😀";
            assertTrue(validator.validate(new CreateAlbumRequest("  " + emoji.repeat(128) + "  ",
                    emoji.repeat(5000), null, List.of())).isEmpty());
            assertFalse(validator.validate(new CreateAlbumRequest("名称", emoji.repeat(5001), null, List.of())).isEmpty());
            assertFalse(validator.validate(new CreateAlbumRequest(emoji.repeat(129), null, null, List.of())).isEmpty());
            assertFalse(validator.validate(new CreateAlbumRequest(" \t\n", null, null, List.of())).isEmpty());
        }
    }

    @Test
    void rejectsMissingNullOrInvalidMomentIdsAndInvalidCover() throws Exception {
        for (String body : List.of("{\"name\":\"名称\"}", "{\"name\":\"名称\",\"momentIds\":null}",
                "{\"name\":\"名称\",\"momentIds\":[null]}", "{\"name\":\"名称\",\"momentIds\":[\"bad\"]}",
                "{\"name\":\"名称\",\"momentIds\":[],\"coverFileId\":\"bad\"}")) {
            mvc.perform(post("/albums").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void createsWith201AndBindsNormalizedName() throws Exception {
        mvc.perform(post("/albums").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  名称  \",\"momentIds\":[]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("code").value("SUCCESS"));
        verify(service).createAlbum(eq(new CreateAlbumRequest("名称", null, null, List.of())), eq(userId));
    }

    @Test
    void bindsDetailUuidAndRejectsMalformedId() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/albums/{albumId}", id).requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isOk());
        verify(service).getAlbum(id, userId);
        mvc.perform(get("/albums/bad").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }
}

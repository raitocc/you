package com.you.youbackend.moment;

import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.controller.MomentController;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.entity.MomentImage;
import com.you.youbackend.moment.mapper.MomentImageMapper;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentTagMapper;
import com.you.youbackend.moment.model.MomentFeedCursor;
import com.you.youbackend.moment.response.MomentFeedItem;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证 JSON 泛型游标绑定、分页参数约束及多查一条生成下一页边界的行为。 */
class MomentPaginationTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID momentId = UUID.fromString("019a0000-0000-7000-8000-000000000001");
    private final Instant time = Instant.parse("2026-01-01T00:00:00Z");
    private final MomentService service = mock(MomentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new MomentController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstPageDefaultsLimitAndReturnsEmptyPage() throws Exception {
        when(service.browseYours(eq(userId), any())).thenReturn(new ResultPage<>(List.of(), null, false));
        mvc.perform(post("/moments/yours").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("data.items").isEmpty())
                .andExpect(jsonPath("data.hasMore").value(false));
        ArgumentCaptor<PageRequest<MomentFeedCursor>> capture = ArgumentCaptor.forClass(PageRequest.class);
        verify(service).browseYours(eq(userId), capture.capture());
        assertNull(capture.getValue().cursor());
        assertEquals(PageRequest.DEFAULT_LIMIT, capture.getValue().limit());
    }

    @Test
    @SuppressWarnings("unchecked")
    void bindsConcreteCursorAndReturnsStructuredNextCursor() throws Exception {
        var cursor = new MomentFeedCursor(time, momentId);
        when(service.browseYours(eq(userId), any())).thenReturn(new ResultPage<>(List.of(), cursor, true));
        mvc.perform(post("/moments/yours").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cursor":{"memoryTime":"2026-01-01T08:00:00+08:00","id":"%s"},"limit":10}
                    """.formatted(momentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.nextCursor.memoryTime").value(time.toString()))
                .andExpect(jsonPath("data.nextCursor.id").value(momentId.toString()))
                .andExpect(jsonPath("data.hasMore").value(true));
        ArgumentCaptor<PageRequest<MomentFeedCursor>> capture = ArgumentCaptor.forClass(PageRequest.class);
        verify(service).browseYours(eq(userId), capture.capture());
        assertEquals(cursor, capture.getValue().cursor());
        assertEquals(10, capture.getValue().limit());
    }

    @Test
    void rejectsInvalidPageSizeAndIncompleteCursor() throws Exception {
        for (String body : new String[]{"{\"limit\":0}", "{\"limit\":101}",
                "{\"cursor\":{}}", "{\"cursor\":{\"memoryTime\":\"2026-01-01T00:00:00Z\"}}",
                "{\"cursor\":{\"memoryTime\":\"2026-01-01T00:00:00Z\",\"id\":\"" + UUID.randomUUID() + "\"}}"}) {
            mvc.perform(post("/moments/yours").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ARGUMENT"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOldStringCursor() throws Exception {
        mvc.perform(post("/moments/yours").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"cursor\":\"old-base64-cursor\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST_BODY"));
        verifyNoInteractions(service);
    }

    @Test
    void serviceUsesLastReturnedRowAsCursorAndConvertsBoundaryToUtc() {
        MomentMapper moments = mock(MomentMapper.class);
        MomentImageMapper images = mock(MomentImageMapper.class);
        MediaService media = mock(MediaService.class);
        MomentService real = new MomentService(moments, images, mock(MomentTagMapper.class), media);
        Moment first = moment(3), last = moment(2), extra = moment(1);
        when(moments.selectFeed(userId, null, null, 3)).thenReturn(List.of(first, last, extra));
        when(images.selectFeedImages(anyCollection())).thenReturn(List.of());
        when(media.findOwnedImageKeys(eq(userId), anyCollection())).thenReturn(Map.of());

        var page = real.browseYours(userId, new PageRequest<>(null, 2));
        assertEquals(2, page.items().size());
        assertTrue(page.hasMore());
        assertEquals(new MomentFeedCursor(time, last.getId()), page.nextCursor());

        LocalDateTime utc = LocalDateTime.ofInstant(time, ZoneOffset.UTC);
        when(moments.selectFeed(userId, utc, last.getId(), 3)).thenReturn(List.of(extra));
        var finalPage = real.browseYours(userId, new PageRequest<>(page.nextCursor(), 2));
        assertEquals(1, finalPage.items().size());
        assertFalse(finalPage.hasMore());
        assertNull(finalPage.nextCursor());
        verify(moments).selectFeed(userId, utc, last.getId(), 3);
    }

    @Test
    void serviceReturnsEmptyPageWithoutQueryingImages() {
        MomentMapper moments = mock(MomentMapper.class);
        MomentImageMapper images = mock(MomentImageMapper.class);
        when(moments.selectFeed(userId, null, null, 21)).thenReturn(List.of());
        MomentService real = new MomentService(moments, images, mock(MomentTagMapper.class), mock(MediaService.class));
        var page = real.browseYours(userId, new PageRequest<>(null, null));
        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        verifyNoInteractions(images);
    }

    @Test
    void feedFiltersUnavailableImagesSignsOnlyFirstValidCoverAndPreservesUnicodePreview() {
        MomentMapper moments = mock(MomentMapper.class);
        MomentImageMapper images = mock(MomentImageMapper.class);
        MediaService media = mock(MediaService.class);
        MomentService real = new MomentService(moments, images, mock(MomentTagMapper.class), media);
        Moment first = moment(3), second = moment(2), extra = moment(1);
        first.setBody("😊".repeat(101));
        when(moments.selectFeed(userId, null, null, 3)).thenReturn(List.of(first, second, extra));
        UUID invalid = UUID.randomUUID(), cover = UUID.randomUUID(), remaining = UUID.randomUUID();
        List<MomentImage> associations = List.of(feedImage(first.getId(), invalid),
                feedImage(first.getId(), cover), feedImage(first.getId(), remaining));
        when(images.selectFeedImages(List.of(first.getId(), second.getId()))).thenReturn(associations);
        when(media.findOwnedImageKeys(userId, List.of(invalid, cover, remaining)))
                .thenReturn(Map.of(cover, "cover-key", remaining, "remaining-key"));
        when(media.createReadUrl("cover-key")).thenReturn("cover-url");

        var page = real.browseYours(userId, new PageRequest<>(null, 2));

        assertEquals("😊".repeat(100), page.items().get(0).bodyPreview());
        assertEquals("cover-url", page.items().get(0).coverUrl());
        assertEquals(2, page.items().get(0).imageCount());
        assertNull(page.items().get(1).coverUrl());
        assertEquals(0, page.items().get(1).imageCount());
        assertEquals(new MomentFeedCursor(time, second.getId()), page.nextCursor());
        verify(images).selectFeedImages(List.of(first.getId(), second.getId()));
        verify(media, times(1)).createReadUrl(anyString());
    }

    private MomentImage feedImage(UUID parentId, UUID fileId) {
        MomentImage image = new MomentImage();
        image.setMomentId(parentId);
        image.setFileId(fileId);
        return image;
    }

    private Moment moment(int suffix) {
        Moment moment = new Moment();
        moment.setId(UUID.fromString("019a0000-0000-7000-8000-%012d".formatted(suffix)));
        moment.setMemoryTime(LocalDateTime.ofInstant(time, ZoneOffset.UTC));
        moment.setBody("正文");
        return moment;
    }
}

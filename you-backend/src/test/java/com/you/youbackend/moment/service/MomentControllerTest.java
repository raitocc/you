package com.you.youbackend.moment.service;

import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.media.entity.MediaFile;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.controller.MomentController;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentRelationMapper;
import com.you.youbackend.user.filter.JwtAuthFilter;
import com.you.youbackend.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过 HTTP 绑定和真实服务逻辑验证瞬间接口，数据库及对象存储由 Mock 提供。 */
class MomentControllerTest {

    private static final String USER = JwtAuthFilter.CURRENT_USER_ID;
    private static final UUID OWNER = UUID.fromString("019a48d0-0000-7000-8000-000000000001");
    private static final UUID MOMENT_ID = UUID.fromString("019a48d0-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("019a48d0-0000-7000-8000-000000000003");
    private static final String RESOURCE = "/moments/" + MOMENT_ID;
    private static final LocalDateTime TIME = LocalDateTime.parse("2026-11-03T02:00:00");
    private static final String ETAG = "\"2026-11-03T02:00:00.000Z\"";

    private final MomentMapper moments = mock(MomentMapper.class);
    private final MomentRelationMapper relations = mock(MomentRelationMapper.class);
    private final MediaService media = mock(MediaService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MomentService service = new MomentService(moments, relations, mock(UserMapper.class),
                media, new MomentCursor("moment-unit-test"));
        mvc = MockMvcBuilders.standaloneSetup(new MomentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void publishCreatesMomentAndNormalizesTags() throws Exception {
        mvc.perform(post("/moments/").requestAttr(USER, OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","body":"海边","tags":[" 旅行 "],"memoryTime":"2026-11-03T10:00:00+08:00"}
                                """.formatted(MOMENT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(MOMENT_ID.toString()))
                .andExpect(jsonPath("$.data.memoryTime").value("2026-11-03T02:00:00.000Z"));
        verify(moments).insert(any(Moment.class));
        verify(relations).saveTag(any(), eq(MOMENT_ID), eq("旅行"), any());
    }

    @Test
    void publishReplaysExistingMomentWithoutWriting() throws Exception {
        when(moments.findById(MOMENT_ID)).thenReturn(moment(MOMENT_ID));
        mvc.perform(post("/moments/").requestAttr(USER, OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","body":"本次内容不覆盖"}
                                """.formatted(MOMENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("原正文"));
        verify(moments, never()).insert(any(Moment.class));
        verify(relations, never()).saveShare(any(), any(), any(), any(), any());
    }

    @Test
    void publishReportsUnavailableTargetId() throws Exception {
        mvc.perform(post("/moments/").requestAttr(USER, OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","body":"原正文","journeyId":"%s"}
                                """.formatted(MOMENT_ID, OTHER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARGET_UNAVAILABLE"))
                .andExpect(jsonPath("$.errors[0].field").value("journeyId"))
                .andExpect(jsonPath("$.errors[0].targetId").value(OTHER.toString()));
    }

    @Test
    void publishRejectsUnownedImageAndDuplicateNormalizedTags() throws Exception {
        MediaFile file = new MediaFile();
        file.setOwnerUserId(OTHER);
        file.setMimeType("image/png");
        when(relations.lockImage(OTHER)).thenReturn(file);
        mvc.perform(post("/moments/").requestAttr(USER, OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","imageFileIds":["%s"]}
                                """.formatted(MOMENT_ID, OTHER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NOT_USABLE"));
        mvc.perform(post("/moments/").requestAttr(USER, OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","body":"正文","tags":[" 旅行 ","旅行"]}
                                """.formatted(MOMENT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        verify(relations, never()).saveImage(any(), any(), any(), eq(1), any());
    }

    @Test
    void browseReturnsNextCursorBoundToUserAndTab() throws Exception {
        when(moments.selectFeed(OWNER, false, null, null, 2))
                .thenReturn(List.of(moment(OTHER), moment(MOMENT_ID)));
        when(moments.selectFeed(OWNER, false, TIME, OTHER, 2))
                .thenReturn(List.of(moment(MOMENT_ID)));

        MvcResult result = mvc.perform(get("/moments").requestAttr(USER, OWNER)
                        .param("tab", "yours").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].collections").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andReturn();
        String cursor = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString())
                .get("data").get("nextCursor").asString();

        mvc.perform(get("/moments").requestAttr(USER, OWNER)
                        .param("tab", "yours").param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(MOMENT_ID.toString()))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get("/moments").requestAttr(USER, OWNER).param("tab", "related").param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/moments").requestAttr(USER, OTHER).param("tab", "yours").param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/moments").requestAttr(USER, OWNER).param("tab", "yours").param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/moments").requestAttr(USER, OWNER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void detailReturnsEtagAndVisibleCollections() throws Exception {
        when(moments.selectById(MOMENT_ID)).thenReturn(moment(MOMENT_ID));
        when(relations.selectAlbums(MOMENT_ID, OWNER))
                .thenReturn(List.of(new MomentRelationMapper.Target(OTHER, "毕业旅行")));
        mvc.perform(get(RESOURCE).requestAttr(USER, OWNER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(jsonPath("$.data.isOwn").value(true))
                .andExpect(jsonPath("$.data.collections[0].entryPath").value("/album/collections/" + OTHER));
    }

    @Test
    void detailHidesPrivateMomentBeforeSigningAnyImage() throws Exception {
        when(moments.selectById(MOMENT_ID)).thenReturn(moment(MOMENT_ID));
        mvc.perform(get(RESOURCE).requestAttr(USER, OTHER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        verifyNoInteractions(media);
    }

    @Test
    void editClearsExplicitNullAndPreservesOmittedFields() throws Exception {
        Moment moment = moment(MOMENT_ID);
        moment.setTitle("旧标题");
        when(moments.lockById(MOMENT_ID)).thenReturn(moment);
        MvcResult result = mvc.perform(patch(RESOURCE).requestAttr(USER, OWNER).header(HttpHeaders.IF_MATCH, ETAG)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":null,\"tags\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").isEmpty())
                .andExpect(jsonPath("$.data.body").value("原正文"))
                .andReturn();
        assertNotEquals(ETAG, result.getResponse().getHeader(HttpHeaders.ETAG));
        verify(moments).updateContent(moment);
        verify(relations).removeTags(eq(MOMENT_ID), any());
        verify(relations, never()).removeImages(any(), any());
    }

    @Test
    void editRejectsStaleVersionAndMissingPrecondition() throws Exception {
        when(moments.lockById(MOMENT_ID)).thenReturn(moment(MOMENT_ID));
        mvc.perform(patch(RESOURCE).requestAttr(USER, OWNER)
                        .header(HttpHeaders.IF_MATCH, "\"2026-11-01T02:00:00.000Z\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"更新\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(patch(RESOURCE).requestAttr(USER, OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"更新\"}"))
                .andExpect(status().isPreconditionRequired());
        verify(moments, never()).updateContent(any());
    }

    @Test
    void editPreservesUnchangedSharesAndDoesNotCreateNewInboxEntries() throws Exception {
        when(moments.lockById(MOMENT_ID)).thenReturn(moment(MOMENT_ID));
        when(relations.lockSharedCircleIds(MOMENT_ID)).thenReturn(List.of(OTHER));
        when(relations.lockCircle(OTHER, OWNER)).thenReturn(new MomentRelationMapper.Target(OTHER, "朋友"));
        mvc.perform(patch(RESOURCE).requestAttr(USER, OWNER).header(HttpHeaders.IF_MATCH, ETAG)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"更新\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("更新"));
        verify(relations).lockCircle(OTHER, OWNER);
        verify(relations, never()).saveShare(any(), any(), any(), any(), any());
        verify(relations, never()).insertInbox(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void editValidatesRetainedImageEvenIfItsFileIsNoLongerAvailable() throws Exception {
        when(moments.lockById(MOMENT_ID)).thenReturn(moment(MOMENT_ID));
        when(relations.selectImageIds(MOMENT_ID)).thenReturn(List.of(OTHER));
        mvc.perform(patch(RESOURCE).requestAttr(USER, OWNER).header(HttpHeaders.IF_MATCH, ETAG)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"更新\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NOT_USABLE"));
        verify(moments, never()).updateContent(any());
    }

    @Test
    void deleteSoftDeletesRelationsAndAllowsRetryWithOldEtag() throws Exception {
        Moment moment = moment(MOMENT_ID);
        when(moments.lockById(MOMENT_ID)).thenReturn(moment);
        when(relations.lockSharedCircleIds(MOMENT_ID)).thenReturn(List.of(OTHER));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete(RESOURCE).requestAttr(USER, OWNER).header(HttpHeaders.IF_MATCH, ETAG))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.deleted").value(true));
        }
        verify(moments).updateContent(moment);
        verify(relations).removeImages(eq(MOMENT_ID), any());
        verify(relations).removeTags(eq(MOMENT_ID), any());
        verify(relations).removeAlbums(eq(MOMENT_ID), any());
        verify(relations).removeShare(eq(MOMENT_ID), eq(OTHER), any());
        verify(relations).removeAllInbox(eq(MOMENT_ID), any());
    }

    @Test
    void deleteRejectsReadableNonAuthor() throws Exception {
        when(moments.lockById(MOMENT_ID)).thenReturn(moment(MOMENT_ID));
        when(relations.selectCircles(MOMENT_ID, OTHER, false))
                .thenReturn(List.of(new MomentRelationMapper.Target(OTHER, "朋友")));
        mvc.perform(delete(RESOURCE).requestAttr(USER, OTHER).header(HttpHeaders.IF_MATCH, ETAG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(moments, never()).updateContent(any());
    }

    private static Moment moment(UUID id) {
        Moment moment = new Moment();
        moment.setId(id);
        moment.setAuthorUserId(OWNER);
        moment.setBody("原正文");
        moment.setMemoryTime(TIME);
        moment.setCreatedAt(TIME);
        moment.setUpdatedAt(TIME);
        return moment;
    }
}

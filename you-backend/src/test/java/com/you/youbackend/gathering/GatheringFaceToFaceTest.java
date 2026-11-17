package com.you.youbackend.gathering;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.gathering.controller.GatheringController;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.FaceToFaceMatch;
import com.you.youbackend.gathering.model.FaceToFaceRules;
import com.you.youbackend.gathering.request.FaceToFacePreviewRequest;
import com.you.youbackend.gathering.request.JoinFaceToFaceRequest;
import com.you.youbackend.gathering.request.OpenFaceToFaceRequest;
import com.you.youbackend.gathering.service.GatheringFaceToFaceRedisService;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证首次开启规则、候选展示、数据库确认加入和三个接口的请求契约。 */
class GatheringFaceToFaceTest {
    private final GatheringFaceToFaceRedisService redisService = mock(GatheringFaceToFaceRedisService.class);
    private final GatheringMapper gatherings = mock(GatheringMapper.class);
    private final GatheringMemberMapper members = mock(GatheringMemberMapper.class);
    private final UserService users = mock(UserService.class);
    private final MomentMapper moments = mock(MomentMapper.class);
    private final GatheringFaceToFaceService service =
            new GatheringFaceToFaceService(redisService, gatherings, members, users);
    private final GatheringService joinService = new GatheringService(gatherings, members, users, moments);
    private final UUID userId = UuidCreator.getTimeOrderedEpoch();

    @Test
    void savesFirstCodeBeforeLocationAndUsesCreationDeadline() {
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        when(gatherings.setFaceCodeIfAbsent(eq(gathering.getId()), eq("0001"), any())).thenReturn(1);

        var result = service.open(gathering.getId(), new OpenFaceToFaceRequest("0001", 30d, 120d), userId);

        assertEquals("0001", result.faceCode());
        assertEquals(gathering.getCreatedAt().toInstant(ZoneOffset.UTC).plus(FaceToFaceRules.WINDOW), result.expiresAt());
        var order = inOrder(gatherings, redisService);
        order.verify(gatherings).setFaceCodeIfAbsent(eq(gathering.getId()), eq("0001"), any());
        order.verify(redisService).saveLocation(eq(gathering.getId()), eq("0001"),
                eq(gathering.getCreatedAt().toInstant(ZoneOffset.UTC)),
                argThat(point -> point.getX() == 120 && point.getY() == 30));
    }

    @Test
    void rejectsMissingForeignDisabledOldAndAlreadyOpenedGatherings() {
        UUID missing = UuidCreator.getTimeOrderedEpoch();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () ->
                service.open(missing, openRequest(), userId)).getStatus());
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () ->
                service.open(gathering.getId(), openRequest(), UuidCreator.getTimeOrderedEpoch())).getStatus());
        gathering.setInviteEnabled(false);
        assertEquals("GATHERING_INVITATION_DISABLED", assertThrows(BusinessException.class, () ->
                service.open(gathering.getId(), openRequest(), userId)).getCode());
        gathering.setInviteEnabled(true);
        gathering.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(11));
        assertEquals("FACE_TO_FACE_WINDOW_EXPIRED", assertThrows(BusinessException.class, () ->
                service.open(gathering.getId(), openRequest(), userId)).getCode());
        gathering.setFaceCode("8888");
        assertEquals(HttpStatus.CONFLICT, assertThrows(BusinessException.class, () ->
                service.open(gathering.getId(), openRequest(), userId)).getStatus());
        verifyNoInteractions(redisService);
        verify(gatherings, never()).setFaceCodeIfAbsent(any(), any(), any());
    }

    @Test
    void refusesConditionalUpdateConflictBeforeWritingLocation() {
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        when(gatherings.setFaceCodeIfAbsent(any(), any(), any())).thenReturn(0);
        assertEquals("FACE_TO_FACE_ALREADY_OPENED", assertThrows(BusinessException.class, () ->
                service.open(gathering.getId(), openRequest(), userId)).getCode());
        verifyNoInteractions(redisService);
    }

    @Test
    void returnsCandidatesWithMembershipAndDistanceOrderAfterDatabaseFiltering() {
        Gathering first = gathering(), second = gathering();
        UUID expired = UuidCreator.getTimeOrderedEpoch();
        var matches = List.of(new FaceToFaceMatch(first.getId(), 10),
                new FaceToFaceMatch(expired, 20), new FaceToFaceMatch(second.getId(), 50));
        when(redisService.findNearby(eq("0001"), eq(30d), eq(120d), any())).thenReturn(matches);
        when(gatherings.selectFaceToFaceCandidates(any(), eq("0001"), any())).thenReturn(List.of(second, first));
        when(users.getPublicUsers(Set.of(userId))).thenReturn(Map.of(userId, profile()));
        when(members.selectActiveGatheringIds(any(), eq(userId))).thenReturn(Set.of(first.getId()));

        var result = service.preview(new FaceToFacePreviewRequest("0001", 30d, 120d), userId);

        assertEquals(List.of(first.getId(), second.getId()), result.stream().map(item -> item.gathering().id()).toList());
        assertTrue(result.get(0).isMember());
        assertFalse(result.get(1).isMember());
        assertEquals(10, result.get(0).distanceMeters());
        assertEquals(50, result.get(1).distanceMeters());
        assertEquals(first.getCreatedAt().toInstant(ZoneOffset.UTC).plus(FaceToFaceRules.WINDOW),
                result.get(0).expiresAt());
        verify(gatherings).selectFaceToFaceCandidates(any(), eq("0001"),
                argThat(boundary -> boundary.isBefore(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(9))));
    }

    @Test
    void truncatesSummaryByCodePointsAndKeepsFullEntityDescription() {
        Gathering gathering = gathering();
        String summary = "文".repeat(99) + "😊";
        gathering.setDescription(summary + "完整介绍的其余内容");
        when(redisService.findNearby(eq("0001"), eq(30d), eq(120d), any()))
                .thenReturn(List.of(new FaceToFaceMatch(gathering.getId(), 10)));
        when(gatherings.selectFaceToFaceCandidates(any(), eq("0001"), any())).thenReturn(List.of(gathering));
        when(users.getPublicUsers(Set.of(userId))).thenReturn(Map.of(userId, profile()));
        when(members.selectActiveGatheringIds(any(), eq(userId))).thenReturn(Set.of());

        var result = service.preview(new FaceToFacePreviewRequest("0001", 30d, 120d), userId);

        assertEquals(summary, result.get(0).gathering().description());
        assertEquals(gathering.getId(), result.get(0).gathering().id());
        assertEquals(profile(), result.get(0).gathering().creator());
        assertEquals(summary + "完整介绍的其余内容", gathering.getDescription());
    }

    @Test
    void keepsShortAndUnsetSummaryDescriptions() {
        Gathering shortDescription = gathering(), unsetDescription = gathering();
        shortDescription.setDescription("一起散步😊");
        when(redisService.findNearby(eq("0001"), eq(30d), eq(120d), any())).thenReturn(List.of(
                new FaceToFaceMatch(shortDescription.getId(), 10), new FaceToFaceMatch(unsetDescription.getId(), 20)));
        when(gatherings.selectFaceToFaceCandidates(any(), eq("0001"), any()))
                .thenReturn(List.of(shortDescription, unsetDescription));
        when(users.getPublicUsers(Set.of(userId))).thenReturn(Map.of(userId, profile()));
        when(members.selectActiveGatheringIds(any(), eq(userId))).thenReturn(Set.of());

        var result = service.preview(new FaceToFacePreviewRequest("0001", 30d, 120d), userId);

        assertEquals("一起散步😊", result.get(0).gathering().description());
        assertNull(result.get(1).gathering().description());
    }

    @Test
    void keepsHidingCandidateWhenCreatorProfileIsMissing() {
        Gathering gathering = gathering();
        when(redisService.findNearby(eq("0001"), eq(30d), eq(120d), any()))
                .thenReturn(List.of(new FaceToFaceMatch(gathering.getId(), 10)));
        when(gatherings.selectFaceToFaceCandidates(any(), eq("0001"), any())).thenReturn(List.of(gathering));
        when(users.getPublicUsers(Set.of(userId))).thenReturn(Map.of());
        when(members.selectActiveGatheringIds(any(), eq(userId))).thenReturn(Set.of());

        assertTrue(service.preview(new FaceToFacePreviewRequest("0001", 30d, 120d), userId).isEmpty());
    }

    @Test
    void returnsEmptyListWithoutDatabaseReadsWhenNoNearbyLocationExists() {
        when(redisService.findNearby(eq("0001"), eq(30d), eq(120d), any())).thenReturn(List.of());
        assertTrue(service.preview(new FaceToFacePreviewRequest("0001", 30d, 120d), userId).isEmpty());
        verifyNoInteractions(gatherings, members, users);
    }

    @Test
    void joinsFromDatabaseWithOneTargetReadAndPreservesExistingMembership() {
        Gathering gathering = gathering();
        gathering.setFaceCode("0001");
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        when(users.getPublicUser(userId)).thenReturn(profile());
        when(members.countActiveMembers(gathering.getId())).thenReturn(2L);
        var request = new JoinFaceToFaceRequest(gathering.getId(), "0001");

        assertEquals(2, joinService.joinFromFaceToFace(request, userId).memberCount());
        verify(gatherings, times(1)).selectById(gathering.getId());
        verify(members).joinOrRestore(argThat(member -> member.getGatheringId().equals(gathering.getId())
                && member.getUserId().equals(userId)));
        clearInvocations(members);
        when(members.isActiveMember(gathering.getId(), userId)).thenReturn(true);
        joinService.joinFromFaceToFace(request, userId);
        verify(members, never()).joinOrRestore(any());
        verifyNoInteractions(redisService);
    }

    @Test
    void rejectsUnavailableDatabaseInvitationsBeforeMemberWrites() {
        UUID missing = UuidCreator.getTimeOrderedEpoch();
        assertThrows(BusinessException.class, () ->
                joinService.joinFromFaceToFace(new JoinFaceToFaceRequest(missing, "0001"), userId));
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        var request = new JoinFaceToFaceRequest(gathering.getId(), "0001");
        assertThrows(BusinessException.class, () -> joinService.joinFromFaceToFace(request, userId));
        gathering.setFaceCode("9999");
        assertThrows(BusinessException.class, () -> joinService.joinFromFaceToFace(request, userId));
        gathering.setFaceCode("0001");
        gathering.setInviteEnabled(false);
        assertThrows(BusinessException.class, () -> joinService.joinFromFaceToFace(request, userId));
        gathering.setInviteEnabled(true);
        gathering.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(11));
        assertThrows(BusinessException.class, () -> joinService.joinFromFaceToFace(request, userId));
        verifyNoInteractions(members, users, moments, redisService);
    }

    @Test
    void bindsThreeInterfacesAndAcceptsJoinWithoutCoordinates() throws Exception {
        var controllerService = mock(GatheringService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GatheringController(controllerService, service, mock(MomentService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        when(gatherings.setFaceCodeIfAbsent(any(), any(), any())).thenReturn(1);
        mvc.perform(post("/gatherings/{id}/face-to-face", gathering.getId())
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faceCode\":\"0001\",\"latitude\":30,\"longitude\":120}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.faceCode").value("0001"));
        gathering.setFaceCode("0001");
        mvc.perform(post("/gatherings/{id}/face-to-face", gathering.getId())
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faceCode\":\"9999\",\"latitude\":31,\"longitude\":121}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FACE_TO_FACE_ALREADY_OPENED"));
        when(redisService.findNearby(eq("0001"), eq(30d), eq(120d), any())).thenReturn(List.of());
        mvc.perform(get("/gatherings/face-to-face/join-preview").param("faceCode", "0001")
                        .param("latitude", "30").param("longitude", "120")
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        when(redisService.findNearby(eq("0001"), eq(30d), eq(120d), any()))
                .thenReturn(List.of(new FaceToFaceMatch(gathering.getId(), 10)));
        when(gatherings.selectFaceToFaceCandidates(any(), eq("0001"), any())).thenReturn(List.of(gathering));
        when(users.getPublicUsers(Set.of(userId))).thenReturn(Map.of(userId, profile()));
        when(members.selectActiveGatheringIds(any(), eq(userId))).thenReturn(Set.of(gathering.getId()));
        mvc.perform(get("/gatherings/face-to-face/join-preview").param("faceCode", "0001")
                        .param("latitude", "30").param("longitude", "120")
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].gathering.id").value(gathering.getId().toString()))
                .andExpect(jsonPath("$.data[0].gathering.creator.id").value(userId.toString()))
                .andExpect(jsonPath("$.data[0].distanceMeters").value(10))
                .andExpect(jsonPath("$.data[0].isMember").value(true));
        mvc.perform(post("/gatherings/face-to-face/join").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gatheringId\":\"" + gathering.getId()
                                + "\",\"faceCode\":\"0001\"}"))
                .andExpect(status().isOk());
        verify(controllerService).joinFromFaceToFace(new JoinFaceToFaceRequest(gathering.getId(), "0001"), userId);
    }

    @Test
    void rejectsInvalidCodeCoordinatesAndSelectedIdAtRequestBoundary() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GatheringController(joinService, service, mock(MomentService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String code : List.of("", "123", "12345", "１２３４", "ab12")) {
            mvc.perform(get("/gatherings/face-to-face/join-preview").param("faceCode", code)
                            .param("latitude", "30").param("longitude", "120")
                            .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                    .andExpect(status().isBadRequest());
        }
        for (String body : List.of("{}", "{\"faceCode\":\"0001\",\"latitude\":86,\"longitude\":120}",
                "{\"faceCode\":\"0001\",\"latitude\":30,\"longitude\":181}")) {
            mvc.perform(post("/gatherings/{id}/face-to-face", UuidCreator.getTimeOrderedEpoch())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                    .andExpect(status().isBadRequest());
        }
        for (String body : List.of("{}", "{\"gatheringId\":\"" + UUID.randomUUID()
                + "\",\"faceCode\":\"0001\"}", "{\"gatheringId\":\"" + UuidCreator.getTimeOrderedEpoch()
                + "\",\"faceCode\":\"123\"}")) {
            mvc.perform(post("/gatherings/face-to-face/join").contentType(MediaType.APPLICATION_JSON)
                            .content(body).requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(redisService, gatherings, members);
    }

    /** 准备仍在创建窗口内的同行，默认尚未开启短码。 */
    private Gathering gathering() {
        Gathering gathering = new Gathering();
        gathering.setId(UuidCreator.getTimeOrderedEpoch());
        gathering.setCreatorUserId(userId);
        gathering.setName("面对面测试");
        gathering.setInviteEnabled(true);
        gathering.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2).truncatedTo(ChronoUnit.MILLIS));
        gathering.setUpdatedAt(gathering.getCreatedAt());
        return gathering;
    }

    /** 准备开启请求，保留短码前导零。 */
    private OpenFaceToFaceRequest openRequest() {
        return new OpenFaceToFaceRequest("0001", 30d, 120d);
    }

    /** 准备创建者公开资料，避免响应组装依赖外部组件。 */
    private PublicUserResponse profile() {
        return new PublicUserResponse(userId, "昵称", null);
    }
}

package com.you.youbackend.gathering;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.gathering.controller.GatheringController;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.FaceToFaceEntry;
import com.you.youbackend.gathering.model.FaceToFaceMatch;
import com.you.youbackend.gathering.model.FaceToFaceRules;
import com.you.youbackend.gathering.request.FaceToFacePreviewRequest;
import com.you.youbackend.gathering.request.JoinFaceToFaceRequest;
import com.you.youbackend.gathering.request.OpenFaceToFaceRequest;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.gathering.store.GatheringFaceToFaceStore;
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

/** 面对面业务边界和三个接口的请求契约测试，Redis 距离算法由集成测试验证。 */
class GatheringFaceToFaceTest {
    private final GatheringFaceToFaceStore store = mock(GatheringFaceToFaceStore.class);
    private final GatheringMapper gatherings = mock(GatheringMapper.class);
    private final GatheringMemberMapper members = mock(GatheringMemberMapper.class);
    private final UserService users = mock(UserService.class);
    private final GatheringService gatheringService = mock(GatheringService.class);
    private final GatheringFaceToFaceService service =
            new GatheringFaceToFaceService(store, gatherings, members, users, gatheringService);
    private final UUID userId = UuidCreator.getTimeOrderedEpoch();

    @Test
    void opensWithDeadlineBasedOnDatabaseCreationTime() {
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        when(store.open(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.open(gathering.getId(), new OpenFaceToFaceRequest("0001", 30d, 120d), userId);
        assertEquals("0001", result.faceCode());
        assertEquals(gathering.getCreatedAt().toInstant(ZoneOffset.UTC).plus(FaceToFaceRules.WINDOW), result.expiresAt());
        verify(store).open(argThat(entry -> entry.latitude() == 30 && entry.longitude() == 120));
    }

    @Test
    void rejectsMissingForeignDisabledAndOldGatheringsBeforeRedisWrites() {
        UUID missing = UuidCreator.getTimeOrderedEpoch();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () ->
                service.open(missing, new OpenFaceToFaceRequest("0001", 30d, 120d), userId)).getStatus());
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () ->
                service.open(gathering.getId(), new OpenFaceToFaceRequest("0001", 30d, 120d),
                        UuidCreator.getTimeOrderedEpoch())).getStatus());
        gathering.setInviteEnabled(false);
        assertEquals("GATHERING_INVITATION_DISABLED", assertThrows(BusinessException.class, () ->
                service.open(gathering.getId(), new OpenFaceToFaceRequest("0001", 30d, 120d), userId)).getCode());
        gathering.setInviteEnabled(true);
        gathering.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(11));
        assertEquals("FACE_TO_FACE_WINDOW_EXPIRED", assertThrows(BusinessException.class, () ->
                service.open(gathering.getId(), new OpenFaceToFaceRequest("0001", 30d, 120d), userId)).getCode());
        verifyNoInteractions(store);
    }

    @Test
    void returnsAllMatchingCandidatesWithMembershipAndDistanceOrder() {
        Gathering first = gathering(), second = gathering();
        var matches = List.of(new FaceToFaceMatch(entry(first), 10), new FaceToFaceMatch(entry(second), 50));
        when(store.findNearby("0001", 30, 120)).thenReturn(matches);
        when(gatherings.selectJoinableByIds(any())).thenReturn(List.of(first, second));
        when(users.getPublicUsers(Set.of(userId))).thenReturn(Map.of(userId, new PublicUserResponse(userId, "昵称", null)));
        when(members.selectActiveGatheringIds(any(), eq(userId))).thenReturn(Set.of(first.getId()));
        var result = service.preview(new FaceToFacePreviewRequest("0001", 30d, 120d), userId);
        assertEquals(List.of(first.getId(), second.getId()), result.stream().map(item -> item.id()).toList());
        assertTrue(result.get(0).isMember());
        assertFalse(result.get(1).isMember());
        assertEquals(10, result.get(0).distanceMeters());
        assertEquals(50, result.get(1).distanceMeters());
        verifyNoInteractions(gatheringService);
    }

    @Test
    void returnsEmptyListWhenNoNearbyEntryExists() {
        when(store.findNearby("0001", 30, 120)).thenReturn(List.of());
        assertTrue(service.preview(new FaceToFacePreviewRequest("0001", 30d, 120d), userId).isEmpty());
        verifyNoInteractions(gatherings, members, users, gatheringService);
    }

    @Test
    void joinsOnlyChosenNearbyTargetAndRejectsMissingMatch() {
        Gathering first = gathering(), second = gathering();
        when(store.findNearby("0001", 30, 120)).thenReturn(List.of(
                new FaceToFaceMatch(entry(first), 10), new FaceToFaceMatch(entry(second), 20)));
        service.join(new JoinFaceToFaceRequest(second.getId(), "0001", 30d, 120d), userId);
        verify(gatheringService).joinFromFaceToFace(second.getId(), userId);
        clearInvocations(gatheringService);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () ->
                service.join(new JoinFaceToFaceRequest(UuidCreator.getTimeOrderedEpoch(),
                        "0001", 30d, 120d), userId)).getStatus());
        verifyNoInteractions(gatheringService);
    }

    @Test
    void bindsThreeInterfacesAndReturnsEmptyCandidatesAs200() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GatheringController(gatheringService, service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        when(store.open(any())).thenAnswer(call -> call.getArgument(0));
        mvc.perform(post("/gatherings/{id}/face-to-face", gathering.getId())
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faceCode\":\"0001\",\"latitude\":30,\"longitude\":120}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.faceCode").value("0001"));
        when(store.findNearby("0001", 30, 120)).thenReturn(List.of());
        mvc.perform(get("/gatherings/face-to-face/join-preview").param("faceCode", "0001")
                        .param("latitude", "30").param("longitude", "120")
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
        when(store.findNearby("0001", 30, 120)).thenReturn(List.of(new FaceToFaceMatch(entry(gathering), 1)));
        mvc.perform(post("/gatherings/face-to-face/join").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gatheringId\":\"" + gathering.getId()
                                + "\",\"faceCode\":\"0001\",\"latitude\":30,\"longitude\":120}"))
                .andExpect(status().isOk());
        verify(gatheringService).joinFromFaceToFace(gathering.getId(), userId);
    }

    @Test
    void rejectsInvalidCodeCoordinatesAndSelectedIdAtRequestBoundary() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GatheringController(gatheringService, service))
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
        mvc.perform(post("/gatherings/face-to-face/join").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatheringId\":\"" + UUID.randomUUID()
                                + "\",\"faceCode\":\"0001\",\"latitude\":30,\"longitude\":120}")
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store, gatherings, gatheringService);
    }

    /** 准备仍在创建窗口内的同行。 */
    private Gathering gathering() {
        Gathering gathering = new Gathering();
        gathering.setId(UuidCreator.getTimeOrderedEpoch());
        gathering.setCreatorUserId(userId);
        gathering.setName("面对面测试");
        gathering.setInviteEnabled(true);
        gathering.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2).truncatedTo(ChronoUnit.MILLIS));
        return gathering;
    }

    /** 准备与同行对应的有效入口。 */
    private FaceToFaceEntry entry(Gathering gathering) {
        return new FaceToFaceEntry(gathering.getId(), "0001", 30, 120, Instant.now().plusSeconds(300));
    }
}
package com.you.youbackend.gathering;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.gathering.controller.GatheringController;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.moment.service.MomentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证三个生命周期入口使用认证身份、无请求体并返回统一响应。 */
class GatheringLifecycleControllerTest {
    @Test
    void delegatesAuthenticatedLifecycleActions() throws Exception {
        GatheringService service = mock(GatheringService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GatheringController(service,
                mock(GatheringFaceToFaceService.class), mock(MomentService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UUID id = UuidCreator.getTimeOrderedEpoch(), userId = UuidCreator.getTimeOrderedEpoch();
        mvc.perform(post("/gatherings/{id}/leave", id).requestAttr("currentUserId", userId))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value("SUCCESS"));
        mvc.perform(post("/gatherings/{id}/end", id).requestAttr("currentUserId", userId))
                .andExpect(status().isOk());
        mvc.perform(delete("/gatherings/{id}", id).requestAttr("currentUserId", userId))
                .andExpect(status().isOk());
        verify(service).leaveGathering(id, userId);
        verify(service).endGathering(id, userId);
        verify(service).dissolveGathering(id, userId);
        mvc.perform(post("/gatherings/invalid/end").requestAttr("currentUserId", userId))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }
}

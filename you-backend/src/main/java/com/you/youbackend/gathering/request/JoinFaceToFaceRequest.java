package com.you.youbackend.gathering.request;

import com.you.youbackend.common.validation.UUIDv7;
import com.you.youbackend.gathering.validation.FaceCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 凭同行 ID 和短码确认加入；附近预览负责帮助用户发现目标。
 * @param gatheringId 用户选择的同行 ID
 * @param faceCode 四位数字短码，保留前导零
 */
@Schema(description = "面对面确认加入请求，仅提交同行 ID 和短码")
public record JoinFaceToFaceRequest(
        @Schema(description = "选中的同行 UUIDv7", format = "uuid",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择要加入的同行")
        @UUIDv7(checkTimestamp = false)
        UUID gatheringId,

        @Schema(description = "四位 ASCII 数字短码，保留前导零", pattern = "^[0-9]{4}$",
                minLength = 4, maxLength = 4, requiredMode = Schema.RequiredMode.REQUIRED)
        @FaceCode
        String faceCode
) {
}
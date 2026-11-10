package com.you.youbackend.gathering.request;

import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * 批量取消关联本人瞬间的请求。
 * @param momentIds 非空的 UUIDv7 列表，元素非空，重复 ID 由业务层合并
 */
@Schema(description = "批量取消关联同行瞬间请求")
public record RemoveGatheringMomentsRequest(
        @Schema(description = "本人瞬间 ID，至少一项，重复 ID 合并", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "请至少选择一个瞬间")
        List<@NotNull(message = "瞬间 ID 不能为空") @UUIDv7(checkTimestamp = false) UUID> momentIds
) {}

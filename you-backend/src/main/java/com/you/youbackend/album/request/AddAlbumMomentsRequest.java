package com.you.youbackend.album.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * 批量添加瞬间请求。
 * @param momentIds 必填且非空的瞬间 ID 列表，元素不可为 null，重复 ID 合并
 */
@Schema(description = "批量添加瞬间请求")
public record AddAlbumMomentsRequest(
        @Schema(description = "待添加的瞬间 ID，至少一项，重复 ID 合并",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "请至少选择一个瞬间")
        List<@NotNull(message = "瞬间 ID 不能为空") UUID> momentIds
) {}

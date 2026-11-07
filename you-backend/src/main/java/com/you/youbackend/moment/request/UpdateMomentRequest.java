package com.you.youbackend.moment.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.you.youbackend.moment.model.MomentLocation;
import com.you.youbackend.moment.model.MomentMood;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 提交瞬间的全部可编辑字段。
 * TODO：补充字段中文注释和 @Schema，明确全量提交、null 清空和空数组的含义。
 */
public record UpdateMomentRequest(
        @JsonProperty(required = true)
        @CodePointLength(max = 255, message = "标题不能超过 255 字符") String title,
        @JsonProperty(required = true) String body,
        @NotNull(message = "请提交图片列表")
        List<@NotNull(message = "图片 ID 不能为空") UUID> imageFileIds,

        @NotNull(message = "请提交标签列表")
        List<@NotNull(message = "标签不能为 null") String> tags,

        @JsonProperty(required = true)
        MomentMood mood,

        @NotNull(message = "请填写发生时间")
        Instant memoryTime,

        @JsonProperty(required = true)
        @Valid
        MomentLocation location
) {}

package com.you.youbackend.moment.request;

import com.you.youbackend.moment.model.MomentMood;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

import java.math.BigDecimal;
import java.util.List;

public record CreateMomentRequest(
        @Schema(format = "uuid", description = "客户端生成的 UUIDv7，最多允许超前服务端 5 分钟")
        @NotNull(message = "请填写瞬间 ID")
        @Pattern(
                regexp = CreateMomentRequest.ID_PATTERN,
                message = "瞬间 ID 必须是 UUIDv7")
        String id,
        @Schema(maxLength = 255)
        @CodePointLength(max = 255, message = "标题不能超过 255 字符") String title,
        String body,
        List<@NotNull(message = "图片 ID 不能为空") @Pattern(
                regexp = CreateMomentRequest.FILE_ID_PATTERN,
                message = "图片 ID 必须是 UUID") String> imageFileIds,
        List<@NotNull(message = "标签不能为 null") String> tags,
        MomentMood mood,
        @Schema(format = "date-time", description = "带时区偏移的 ISO 8601 发生时间")
        @NotNull(message = "请填写发生时间") String memoryTime,
        @Valid Location location
) {
    public static final String ID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}";
    public static final String FILE_ID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    public record Location(
            @DecimalMin(value = "-90", message = "纬度不能小于 -90")
            @DecimalMax(value = "90", message = "纬度不能大于 90")
            BigDecimal latitude,
            @DecimalMin(value = "-180", message = "经度不能小于 -180")
            @DecimalMax(value = "180", message = "经度不能大于 180")
            BigDecimal longitude,
            @Schema(maxLength = 255)
            @CodePointLength(max = 255, message = "地点名称不能超过 255 字符") String name,
            @Schema(maxLength = 512)
            @CodePointLength(max = 512, message = "地点地址不能超过 512 字符") String address
    ) {}
}

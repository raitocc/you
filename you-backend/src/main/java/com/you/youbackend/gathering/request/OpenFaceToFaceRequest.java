package com.you.youbackend.gathering.request;

import com.you.youbackend.gathering.validation.FaceCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 开启面对面短码入口的请求，双方定位使用同一坐标系。
 * @param faceCode 四位数字短码，保留前导零
 * @param latitude 当前纬度，采用 Redis GEO 支持的范围
 * @param longitude 当前经度，范围 -180～180
 */
@Schema(description = "开启面对面短码入口请求")
public record OpenFaceToFaceRequest(
        @Schema(description = "四位 ASCII 数字短码，保留前导零", pattern = "^[0-9]{4}$",
                minLength = 4, maxLength = 4, requiredMode = Schema.RequiredMode.REQUIRED)
        @FaceCode
        String faceCode,

        @Schema(description = "当前纬度，与其他参与者采用同一坐标系；范围由 Redis GEO 决定",
                minimum = "-85.05112878", maximum = "85.05112878", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请提供当前纬度")
        @DecimalMin(value = "-85.05112878", message = "纬度超出支持范围")
        @DecimalMax(value = "85.05112878", message = "纬度超出支持范围")
        Double latitude,

        @Schema(description = "当前经度，与其他参与者采用同一坐标系",
                minimum = "-180", maximum = "180", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请提供当前经度")
        @DecimalMin(value = "-180", message = "经度不能小于 -180")
        @DecimalMax(value = "180", message = "经度不能大于 180")
        Double longitude
) {
}
package com.you.youbackend.moment.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 瞬间发生地点。 */
@Schema(description = "瞬间发生地点")
public record MomentLocation(
        @Schema(description = "地点名称") @Size(max = 255) String name,
        @Schema(description = "地点地址") @Size(max = 512) String address,
        @Schema(description = "纬度") @Digits(integer = 2, fraction = 7)
        @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @Schema(description = "经度") @Digits(integer = 3, fraction = 7)
        @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude
) {
}

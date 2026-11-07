package com.you.youbackend.moment.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.hibernate.validator.constraints.CodePointLength;

import java.math.BigDecimal;

/**
 * 瞬间地点，供创建请求、修改请求、业务处理和详情响应共同使用。
 *
 * <p>请求通过 @Valid 级联校验坐标范围和文本长度，业务层负责检查经纬度成对填写并统一精度。
 * 实体仍使用独立的经纬度、地名和地址字段，在 Service 中完成赋值转换；数据库结构保持不变。</p>
 *
 * @param latitude 纬度，可空；提供时须同时提供经度
 * @param longitude 经度，可空；提供时须同时提供纬度
 * @param name 地点名称，可空，允许只记录名称和地址
 * @param address 详细地址，可空
 */
@Schema(description = "瞬间地点，支持经纬度、地名和详细地址")
public record MomentLocation(
        @Schema(description = "纬度，与经度成对填写", example = "31.2304")
        @DecimalMin(value = "-90", message = "纬度不能小于 -90")
        @DecimalMax(value = "90", message = "纬度不能大于 90")
        BigDecimal latitude,

        @Schema(description = "经度，与纬度成对填写", example = "121.4737")
        @DecimalMin(value = "-180", message = "经度不能小于 -180")
        @DecimalMax(value = "180", message = "经度不能大于 180")
        BigDecimal longitude,

        @Schema(description = "地点名称", maxLength = 255)
        @CodePointLength(max = 255, message = "地点名称不能超过 255 字符")
        String name,

        @Schema(description = "详细地址", maxLength = 512)
        @CodePointLength(max = 512, message = "地点地址不能超过 512 字符")
        String address
) {}

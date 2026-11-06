package com.you.youbackend.moment.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * 发布瞬间请求，客户端在重试时使用同一个 UUIDv7。
 */
@Getter
@Setter
@Schema(description = "发布瞬间请求")
public class PublishMomentRequest extends EditMomentRequest {

    @Schema(description = "客户端预生成的瞬间 UUIDv7")
    @NotNull(message = "请提供瞬间 ID")
    private UUID id;
}

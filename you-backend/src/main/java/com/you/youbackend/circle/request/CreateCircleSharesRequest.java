package com.you.youbackend.circle.request;
import com.you.youbackend.circle.model.CircleShareObjectType;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.UniqueElements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
/** 把自己的一个瞬间或时光分享到指定圈子。至少选一个圈子，每个圈子只能选一次。 */
public record CreateCircleSharesRequest(
        @NotNull @Schema(description = "分享类型，moment 或 album") CircleShareObjectType objectType,
        @NotNull @Schema(description = "分享的原始对象（瞬间、时光）的 ID") UUID objectId,
        @NotEmpty @UniqueElements @Schema(description = "目标圈子 ID，至少一个且不重复") List<@NotNull UUID> circleIds) {}

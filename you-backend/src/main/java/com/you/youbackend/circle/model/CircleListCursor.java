package com.you.youbackend.circle.model;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
/** 成员关系分页位置；时间与 ID 必须成对提供，id 为成员关系 ID。 */
public record CircleListCursor(@NotNull Instant joinedAt, @NotNull UUID id) {}

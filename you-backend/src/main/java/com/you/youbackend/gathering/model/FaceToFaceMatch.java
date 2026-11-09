package com.you.youbackend.gathering.model;

import java.util.UUID;

/**
 * GEO 附近查询结果，同行状态、短码与邀请期限由 MySQL 校验。
 * @param gatheringId 附近的同行 ID
 * @param distanceMeters 距查询位置的距离，单位米
 */
public record FaceToFaceMatch(UUID gatheringId, double distanceMeters) {
}
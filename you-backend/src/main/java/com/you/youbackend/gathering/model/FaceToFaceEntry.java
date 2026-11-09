package com.you.youbackend.gathering.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Redis 中的面对面入口，发起位置与截止时间在首次开启后保持原样。
 * @param gatheringId 同行 ID
 * @param faceCode 四位数字短码，保留前导零
 * @param latitude 发起纬度，与请求方使用同一坐标系
 * @param longitude 发起经度，与请求方使用同一坐标系
 * @param expiresAt 同行创建时间加面对面窗口，UTC
 */
public record FaceToFaceEntry(UUID gatheringId, String faceCode, double latitude,
                              double longitude, Instant expiresAt) {
}
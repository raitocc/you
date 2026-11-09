package com.you.youbackend.gathering.model;

/**
 * 位置索引查得的有效入口及距离。
 * @param entry 尚未到期的入口
 * @param distanceMeters 查询者距发起位置的距离，单位米
 */
public record FaceToFaceMatch(FaceToFaceEntry entry, double distanceMeters) {
}
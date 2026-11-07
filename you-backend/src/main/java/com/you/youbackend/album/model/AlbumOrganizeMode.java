package com.you.youbackend.album.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** 整理方式，JSON 和数据库均沿用小写枚举名称，无需自定义转换。 */
@Schema(description = "整理方式：manual 手动，ai AI 整理")
public enum AlbumOrganizeMode {
    /** 手动整理。 */
    manual,
    /** AI 整理。 */
    ai
}

package com.you.youbackend.gathering.model;

import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

/** 按同行分组的数量投影，仅供批量统计查询使用。 */
@Getter
@Setter
public class GatheringCount {
    /** 被统计的同行 ID。 */
    private UUID gatheringId;
    /** 当前有效记录数。 */
    private long count;
}

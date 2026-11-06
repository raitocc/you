package com.you.youbackend.moment.service;

import com.you.youbackend.common.exception.BusinessException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/** 关联或分享目标已失效，保留调用方提交的问题目标。 */
@Getter
public class MomentTargetUnavailableException extends BusinessException {

    private final String field;
    private final UUID targetId;

    public MomentTargetUnavailableException(String field, UUID targetId) {
        super(HttpStatus.CONFLICT, "TARGET_UNAVAILABLE", "同行或圈子已失效，或当前用户不是成员");
        this.field = field;
        this.targetId = targetId;
    }
}

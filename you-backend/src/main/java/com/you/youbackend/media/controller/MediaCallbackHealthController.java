package com.you.youbackend.media.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对象存储回调服务的连通性检查。
 */
@Slf4j
@RestController
@Tag(name = "媒体", description = "RustFs 回调 HealthCheck")

public class MediaCallbackHealthController {

    @Operation(
            summary = "健康检查",
            description = "返回 200 OK")
    @RequestMapping(value = "/", method = RequestMethod.HEAD)
    public ResponseEntity<Void> check() {
        log.info("RustFs 回调请求");
        return ResponseEntity.ok().build();
    }
}
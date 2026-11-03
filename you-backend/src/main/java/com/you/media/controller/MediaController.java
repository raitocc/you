package com.you.media.controller;

import com.you.common.exception.BusinessException;
import com.you.common.response.ApiResponse;
import com.you.common.util.Input;
import com.you.media.service.UploadService;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "媒体")
@RestController
@RequestMapping("/media/uploads")
public class MediaController {
  private final UploadService uploads;

  public MediaController(UploadService u) {
    uploads = u;
  }

  @PostMapping
  public ResponseEntity<?> apply(
      @RequestAttribute UUID userId, @RequestBody Map<String, Object> body) {
    Input.fields(body, "mimeType", "byteSize");
    Object size = body.get("byteSize");
    if (!(size instanceof Integer) && !(size instanceof Long)) throw BusinessException.invalid();
    return ResponseEntity.status(201)
        .body(
            ApiResponse.ok(
                uploads.apply(
                    userId, Input.string(body, "mimeType"), ((Number) size).longValue())));
  }

  @PostMapping("/{uploadId}/complete")
  public ApiResponse<?> complete(@RequestAttribute UUID userId, @PathVariable UUID uploadId) {
    return ApiResponse.ok(uploads.complete(userId, uploadId));
  }
}

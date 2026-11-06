package com.you.youbackend.moment.controller;

import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.PageResult;
import com.you.youbackend.common.response.Result;
import com.you.youbackend.moment.request.EditMomentRequest;
import com.you.youbackend.moment.request.PublishMomentRequest;
import com.you.youbackend.moment.response.DeleteMomentResponse;
import com.you.youbackend.moment.response.MomentResponse;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.moment.service.MomentTargetUnavailableException;
import com.you.youbackend.user.filter.JwtAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

/** 瞬间发布、浏览、详情、编辑和删除接口。 */
@RestController
@RequestMapping("/moments")
@RequiredArgsConstructor
@Tag(name = "瞬间")
public class MomentController {

    private final MomentService service;

    /** 发布瞬间，首次创建返回 201，同一 ID 重试返回 200。 */
    @PostMapping("/")
    @Operation(summary = "发布瞬间")
    @ApiResponse(responseCode = "201", description = "首次发布成功")
    @ApiResponse(responseCode = "200", description = "返回已发布的瞬间")
    @ApiResponse(responseCode = "409", description = "目标失效或发布 ID 冲突")
    public ResponseEntity<Result<MomentResponse>> publish(
            @Valid @RequestBody PublishMomentRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        MomentService.PublishResult result = service.publish(userId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(Result.ok(result.moment()));
    }

    /** 按 Tab 获取当前用户有权访问的瞬间信息流。 */
    @GetMapping
    @Operation(summary = "浏览瞬间信息流")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "Tab、每页条数或游标无效")
    public Result<PageResult<MomentResponse>> browse(
            @RequestParam String tab,
            @Valid @ModelAttribute @ParameterObject PageRequest page,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(service.browse(userId, tab, page));
    }

    /** 返回当前用户可访问的瞬间详情及版本。 */
    @GetMapping("/{momentId}")
    @Operation(summary = "获取瞬间详情")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "404", description = "瞬间不存在或不可访问")
    public ResponseEntity<Result<MomentResponse>> detail(
            @PathVariable UUID momentId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return versioned(service.detail(userId, momentId));
    }

    /** 按 If-Match 校验版本并部分更新瞬间。 */
    @PatchMapping("/{momentId}")
    @Operation(summary = "编辑瞬间")
    @ApiResponse(responseCode = "200", description = "更新成功")
    @ApiResponse(responseCode = "409", description = "版本冲突或目标失效")
    @ApiResponse(responseCode = "428", description = "缺少 If-Match")
    public ResponseEntity<Result<MomentResponse>> edit(
            @PathVariable UUID momentId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody EditMomentRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return versioned(service.edit(userId, momentId, ifMatch, request));
    }

    /** 按 If-Match 校验版本并软删除瞬间。 */
    @DeleteMapping("/{momentId}")
    @Operation(summary = "删除瞬间")
    @ApiResponse(responseCode = "200", description = "删除成功或已删除")
    @ApiResponse(responseCode = "409", description = "版本冲突")
    @ApiResponse(responseCode = "428", description = "缺少 If-Match")
    public Result<DeleteMomentResponse> delete(
            @PathVariable UUID momentId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(service.delete(userId, momentId, ifMatch));
    }

    /** 返回请求中无法关联或分享的目标。 */
    @ExceptionHandler(MomentTargetUnavailableException.class)
    ResponseEntity<TargetError> handleTargetUnavailable(MomentTargetUnavailableException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new TargetError(
                exception.getCode(), exception.getMessage(), null,
                List.of(new FieldError(exception.getField(), exception.getMessage(), exception.getTargetId()))));
    }

    /** 返回瞬间接口的参数校验错误。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<Result<Void>> handleInvalidParameter(Exception exception) {
        String message = "请求参数不符合要求";
        if (exception instanceof MethodArgumentNotValidException invalid
                && invalid.getBindingResult().getFieldError() != null) {
            message = invalid.getBindingResult().getFieldError().getDefaultMessage();
        }
        return ResponseEntity.badRequest().body(Result.fail("INVALID_PARAMETER", message));
    }

    private static ResponseEntity<Result<MomentResponse>> versioned(MomentResponse moment) {
        return ResponseEntity.ok().eTag(moment.updatedAt()).body(Result.ok(moment));
    }

    record TargetError(String code, String message, Void data, List<FieldError> errors) {}

    record FieldError(String field, String reason, UUID targetId) {}
}

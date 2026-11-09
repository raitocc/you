package com.you.youbackend.gathering.controller;

import com.you.youbackend.common.response.Result;
import com.you.youbackend.common.validation.UUIDv7;
import com.you.youbackend.gathering.request.OpenFaceToFaceRequest;
import com.you.youbackend.gathering.request.FaceToFacePreviewRequest;
import com.you.youbackend.gathering.request.JoinFaceToFaceRequest;
import com.you.youbackend.gathering.response.FaceToFaceOpenResponse;
import com.you.youbackend.gathering.response.FaceToFaceGatheringItem;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.gathering.request.CreateGatheringRequest;
import com.you.youbackend.gathering.request.GatheringJoinPreviewRequest;
import com.you.youbackend.gathering.request.JoinGatheringRequest;
import com.you.youbackend.gathering.response.GatheringJoinPreviewResponse;
import com.you.youbackend.gathering.response.GatheringResponse;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 同行接口入口，使用统一认证上下文委托业务处理。 */
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "同行")
@RestController
@RequestMapping("/gatherings")
@RequiredArgsConstructor
public class GatheringController {
    private final GatheringService gatheringService;
    private final GatheringFaceToFaceService faceToFaceService;

    /** 创建者为创建后 10 分钟内的同行开启面对面入口。 */
    @Operation(summary = "开启面对面短码加入", operationId = "04_open_face_to_face",
            description = "提交四位数字短码及当前位置。截止时间固定为同行创建后 10 分钟，重复开启复用原入口。")
    @ApiResponse(responseCode = "200", description = "返回实际生效的短码与截止时间")
    @ApiResponse(responseCode = "400", description = "短码或坐标无效、邀请关闭或创建窗口已结束")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是创建者")
    @ApiResponse(responseCode = "404", description = "同行不存在或已解散")
    @PostMapping(value = "/{gatheringId}/face-to-face", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<FaceToFaceOpenResponse> openFaceToFace(
            @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @Valid @RequestBody OpenFaceToFaceRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 开启权限、时间窗口及 Redis 入口写入由面对面业务层处理。
        return Result.ok(faceToFaceService.open(gatheringId, request, userId));
    }

    /** 查找附近同码的有效同行，零匹配返回空列表，多个匹配全部展示。 */
    @Operation(summary = "查找附近面对面同行", operationId = "05_preview_face_to_face",
            description = "按四位短码和当前位置查找 100 米内尚未过期、开放邀请的同行，按距离从近到远返回候选列表。")
    @ApiResponse(responseCode = "200", description = "返回候选列表，零匹配为 []")
    @ApiResponse(responseCode = "400", description = "短码或坐标无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @GetMapping("/face-to-face/join-preview")
    public Result<List<FaceToFaceGatheringItem>> previewFaceToFace(
            @Valid @ModelAttribute @ParameterObject FaceToFacePreviewRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 查询阶段只返回附近候选，成员关系在确认加入时建立。
        return Result.ok(faceToFaceService.preview(request, userId));
    }

    /** 用户选择目标后，通过短码和当前坐标确认加入。 */
    @Operation(summary = "通过面对面短码确认加入", operationId = "06_join_face_to_face",
            description = "提交候选中的同行 ID、短码及当前位置，重新校验 100 米距离、入口期限与邀请开关，再建立或恢复成员关系。")
    @ApiResponse(responseCode = "200", description = "加入成功或已是有效成员，返回同行信息")
    @ApiResponse(responseCode = "400", description = "同行 ID、短码或坐标无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "404", description = "入口过期、超出范围、短码不匹配或目标同行不可加入")
    @PostMapping(value = "/face-to-face/join", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<GatheringResponse> joinFaceToFace(
            @Valid @RequestBody JoinFaceToFaceRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 按用户选中的同行确认加入，复用现有成员维护规则。
        return Result.ok(faceToFaceService.join(request, userId));
    }

    /** 根据长期邀请码展示加入目标，返回当前成员标记，供前端选择进入或确认加入。 */
    @Operation(summary = "查看邀请码加入预览", operationId = "02_preview_gathering_join",
            description = "扫码和手动输入共用长期邀请码。返回可加入同行的基本信息，预览阶段只读取数据。")
    @ApiResponse(responseCode = "200", description = "查询成功，返回加入预览")
    @ApiResponse(responseCode = "400", description = "邀请码为空白或超过 16 字符")
    @ApiResponse(responseCode = "401", description = "登录凭证无效")
    @ApiResponse(responseCode = "404", description = "邀请码无效、邀请关闭或同行已解散")
    @GetMapping("/join-preview")
    public Result<GatheringJoinPreviewResponse> previewJoin(
            @Valid @ModelAttribute @ParameterObject GatheringJoinPreviewRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 将查询参数和认证用户交给业务层，只加载加入预览。
        return Result.ok(gatheringService.previewJoin(request, userId));
    }

    /** 用户确认后建立或恢复成员关系，重复加入返回当前同行信息。 */
    @Operation(summary = "通过邀请码确认加入", operationId = "03_join_gathering",
            description = "按提交时状态重新校验邀请码。恢复原成员关系时保留 ID 与创建时间，原瞬间关联保持原样。")
    @ApiResponse(responseCode = "200", description = "加入成功或已是有效成员，返回同行信息")
    @ApiResponse(responseCode = "400", description = "邀请码为空白或超过 16 字符")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "404", description = "邀请码无效、邀请关闭或同行已解散")
    @PostMapping(value = "/join", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<GatheringResponse> joinGathering(
            @Valid @RequestBody JoinGatheringRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 成员资格与事务由业务层统一维护，接口返回最新同行信息。
        return Result.ok(gatheringService.joinGathering(request, userId));
    }

    /** 创建同行并将本人加入为初始成员，返回同行信息及长期邀请码。 */
    @Operation(summary = "创建同行", operationId = "01_create_gathering",
            description = "创建进行中的同行，邀请默认开启。同行与创建者成员关系在同一事务内保存，允许暂时没有瞬间。")
    @ApiResponse(responseCode = "200", description = "创建成功，返回同行信息")
    @ApiResponse(responseCode = "400", description = "名称或介绍无效")
    @ApiResponse(responseCode = "401", description = "登录凭证无效，或账号不存在、已注销")
    @ApiResponse(responseCode = "409", description = "邀请码生成冲突，请重新创建")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<GatheringResponse> createGathering(
            @Valid @RequestBody CreateGatheringRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 使用认证用户创建同行，业务层统一维护初始成员和事务。
        return Result.ok(gatheringService.createGathering(request, userId));
    }
}
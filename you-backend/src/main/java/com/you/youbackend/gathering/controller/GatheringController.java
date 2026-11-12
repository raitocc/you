package com.you.youbackend.gathering.controller;

import com.you.youbackend.common.request.PageQuery;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.Result;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.common.validation.UUIDv7;
import com.you.youbackend.gathering.model.GatheringListCursor;
import com.you.youbackend.gathering.model.GatheringMemberCursor;
import com.you.youbackend.gathering.model.GatheringMomentCursor;
import com.you.youbackend.gathering.request.AddGatheringMomentsRequest;
import com.you.youbackend.gathering.request.CreateGatheringRequest;
import com.you.youbackend.gathering.request.FaceToFacePreviewRequest;
import com.you.youbackend.gathering.request.GatheringJoinPreviewRequest;
import com.you.youbackend.gathering.request.JoinFaceToFaceRequest;
import com.you.youbackend.gathering.request.JoinGatheringRequest;
import com.you.youbackend.gathering.request.OpenFaceToFaceRequest;
import com.you.youbackend.gathering.request.RemoveGatheringMomentsRequest;
import com.you.youbackend.gathering.request.UpdateGatheringRequest;
import com.you.youbackend.gathering.response.FaceToFaceCandidateResponse;
import com.you.youbackend.gathering.response.FaceToFaceOpenResponse;
import com.you.youbackend.gathering.response.GatheringJoinPreviewResponse;
import com.you.youbackend.gathering.response.GatheringResponse;
import com.you.youbackend.gathering.response.GatheringSummaryResponse;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.moment.response.MomentResponse;
import com.you.youbackend.moment.response.MomentSummaryResponse;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final MomentService momentService;

    /** 主动退出同行，重复操作成功，具体校验与事务由业务层处理。 */
    @Operation(summary = "主动退出同行", operationId = "15_leave_gathering")
    @ApiResponse(responseCode = "200", description = "操作成功，data 为 null")
    @ApiResponse(responseCode = "400", description = "同行 ID 无效，或创建者尝试退出")
    @ApiResponse(responseCode = "401", description = "登录凭证无效")
    @ApiResponse(responseCode = "403", description = "当前用户从未加入该同行")
    @ApiResponse(responseCode = "404", description = "同行不存在或已解散")
    @PostMapping("/{gatheringId}/leave")
    public Result<Void> leaveGathering(
            @Parameter(description = "同行 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 使用已认证身份执行主动退出同行。
        gatheringService.leaveGathering(gatheringId, userId);
        return Result.ok(null);
    }

    /** 结束同行，重复操作成功，具体校验与事务由业务层处理。 */
    @Operation(summary = "结束同行", operationId = "16_end_gathering")
    @ApiResponse(responseCode = "200", description = "操作成功，data 为 null")
    @ApiResponse(responseCode = "400", description = "同行 ID 无效")
    @ApiResponse(responseCode = "401", description = "登录凭证无效")
    @ApiResponse(responseCode = "403", description = "当前用户不是创建者")
    @ApiResponse(responseCode = "404", description = "同行不存在或已解散")
    @PostMapping("/{gatheringId}/end")
    public Result<Void> endGathering(
            @Parameter(description = "同行 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 使用已认证身份执行结束同行。
        gatheringService.endGathering(gatheringId, userId);
        return Result.ok(null);
    }

    /** 解散同行，重复操作成功，具体校验与事务由业务层处理。 */
    @Operation(summary = "解散同行", operationId = "17_dissolve_gathering")
    @ApiResponse(responseCode = "200", description = "操作成功，data 为 null")
    @ApiResponse(responseCode = "400", description = "同行 ID 无效")
    @ApiResponse(responseCode = "401", description = "登录凭证无效")
    @ApiResponse(responseCode = "403", description = "当前用户不是创建者")
    @ApiResponse(responseCode = "404", description = "同行不存在")
    @DeleteMapping("/{gatheringId}")
    public Result<Void> dissolveGathering(
            @Parameter(description = "同行 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 使用已认证身份执行解散同行。
        gatheringService.dissolveGathering(gatheringId, userId);
        return Result.ok(null);
    }

    /** 同行成员浏览共同经历时间线，按发生时间及 ID 正序。 */
    @Operation(summary = "浏览同行中的瞬间", operationId = "13_list_gathering_moments")
    @ApiResponse(responseCode = "200", description = "查询成功，返回包含作者的瞬间摘要页")
    @ApiResponse(responseCode = "400", description = "同行 ID、分页参数或游标无效")
    @ApiResponse(responseCode = "401", description = "登录凭证无效")
    @ApiResponse(responseCode = "403", description = "当前用户不是有效成员")
    @ApiResponse(responseCode = "404", description = "同行不存在或已解散")
    @Parameters({
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页数量，默认 20，范围 1～100",
                    schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
            @Parameter(name = "cursor.memoryTime", in = ParameterIn.QUERY, description = "上一页末项发生时间，首屏省略",
                    schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "cursor.id", in = ParameterIn.QUERY, description = "与发生时间成组提交的上一页末项 UUIDv7",
                    schema = @Schema(type = "string", format = "uuid"))
    })
    @GetMapping("/{gatheringId}/moments")
    public Result<ResultPage<MomentSummaryResponse, GatheringMomentCursor>> browseMoments(
            @Parameter(description = "同行 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @Parameter(hidden = true) @PageQuery PageRequest<GatheringMomentCursor> request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 委托瞬间业务完成同行权限校验及摘要批量组装。
        return Result.ok(momentService.browseGatheringMoments(gatheringId, request, userId));
    }

    /** 通过指定同行读取共享瞬间，检查有效成员资格与当前关联。 */
    @Operation(summary = "查看同行共享瞬间详情", operationId = "14_get_gathering_moment")
    @ApiResponse(responseCode = "200", description = "查询成功，返回包含作者的完整详情")
    @ApiResponse(responseCode = "400", description = "同行或瞬间 ID 无效")
    @ApiResponse(responseCode = "401", description = "登录凭证无效")
    @ApiResponse(responseCode = "403", description = "当前用户不是有效成员")
    @ApiResponse(responseCode = "404", description = "同行失效、瞬间不存在、已删除或已移出同行")
    @GetMapping("/{gatheringId}/moments/{momentId}")
    public Result<MomentResponse> getMoment(
            @Parameter(description = "同行 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @Parameter(description = "瞬间 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID momentId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 当前入口按同行关系授权，与本人瞬间详情保留独立的权限语义。
        return Result.ok(momentService.getGatheringMoment(gatheringId, momentId, userId));
    }

    /** 批量关联本人瞬间，已完成的重复操作返回成功。 */
    @Operation(summary = "批量关联同行瞬间", operationId = "11_add_gathering_moments")
    @ApiResponse(responseCode = "200", description = "操作成功，data 为 null")
    @ApiResponse(responseCode = "400", description = "参数无效、瞬间不存在、已删除或非本人，或同行已结束")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是有效成员")
    @ApiResponse(responseCode = "404", description = "同行不存在或已解散")
    @ApiResponse(responseCode = "409", description = "瞬间已关联其他同行，或关联状态在写入前变化；整批回滚")
    @PostMapping(value = "/{gatheringId}/moments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> addMoments(
            @Parameter(description = "同行 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @Valid @RequestBody AddGatheringMomentsRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 统一身份和参数交给业务层，整批业务校验通过后维护关联。
        gatheringService.addMoments(gatheringId, request, userId);
        return Result.ok(null);
    }

    /** 批量取消关联本人瞬间，已完成的重复操作返回成功。 */
    @Operation(summary = "批量取消关联同行瞬间", operationId = "12_remove_gathering_moments")
    @ApiResponse(responseCode = "200", description = "操作成功，data 为 null")
    @ApiResponse(responseCode = "400", description = "参数无效、瞬间不存在、已删除或非本人")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是有效成员")
    @ApiResponse(responseCode = "404", description = "同行不存在或已解散")
    @PostMapping(value = "/{gatheringId}/moments/remove", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> removeMoments(
            @Parameter(description = "同行 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @Valid @RequestBody RemoveGatheringMomentsRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 统一身份和参数交给业务层，整批业务校验通过后维护关联。
        gatheringService.removeMoments(gatheringId, request, userId);
        return Result.ok(null);
    }

    /** 浏览自己的同行，使用 GET 查询参数绑定业务游标。 */
    @Operation(summary = "浏览自己的同行", operationId = "07_list_gatherings")
    @ApiResponse(responseCode = "200", description = "查询成功，返回游标分页结果；用户公开资料查不到时为 null")
    @ApiResponse(responseCode = "400", description = "分页参数、游标或同行 ID 无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @Parameters({
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页数量，默认 20，范围 1～100",
                    schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
            @Parameter(name = "cursor.createdAt", in = ParameterIn.QUERY, description = "上一页末项同行创建时间，首屏省略",
                    schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "cursor.id", in = ParameterIn.QUERY, description = "与游标时间成组提交的同行 ID",
                    schema = @Schema(type = "string", format = "uuid"))
    })
    @GetMapping
    public Result<ResultPage<GatheringSummaryResponse, GatheringListCursor>> browseGatherings(
            @Parameter(hidden = true) @PageQuery PageRequest<GatheringListCursor> request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 查询范围及访问权限由业务层确定，游标绑定和基础校验沿用统一机制。
        return Result.ok(gatheringService.browseGatherings(request, userId));
    }

    /** 浏览同行成员，使用 GET 查询参数绑定业务游标。 */
    @Operation(summary = "浏览同行成员", operationId = "10_list_gathering_members")
    @ApiResponse(responseCode = "200", description = "查询成功，返回游标分页结果；用户公开资料查不到时为 null")
    @ApiResponse(responseCode = "400", description = "分页参数、游标或同行 ID 无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是有效成员")
    @ApiResponse(responseCode = "404", description = "同行不存在或已解散")
    @Parameters({
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页数量，默认 20，范围 1～100",
                    schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
            @Parameter(name = "cursor.createdAt", in = ParameterIn.QUERY, description = "上一页末项成员关系创建时间，首屏省略",
                    schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "cursor.userId", in = ParameterIn.QUERY, description = "与游标时间成组提交的用户 ID",
                    schema = @Schema(type = "string", format = "uuid"))
    })
    @GetMapping("/{gatheringId}/members")
    public Result<ResultPage<PublicUserResponse, GatheringMemberCursor>> browseMembers(
            @Parameter(description = "同行 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @Parameter(hidden = true) @PageQuery PageRequest<GatheringMemberCursor> request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 查询范围及访问权限由业务层确定，游标绑定和基础校验沿用统一机制。
        return Result.ok(gatheringService.browseMembers(gatheringId, request, userId));
    }

    /** 有效成员查看同行基本信息，成员与瞬间通过各自分页接口加载。 */
    @Operation(summary = "查看同行信息", operationId = "08_get_gathering")
    @ApiResponse(responseCode = "200", description = "查询成功，返回完整同行信息")
    @ApiResponse(responseCode = "400", description = "同行 ID 无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是有效成员")
    @ApiResponse(responseCode = "404", description = "同行不存在或已解散")
    @GetMapping("/{gatheringId}")
    public Result<GatheringResponse> getGathering(
            @Parameter(description = "同行 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 由业务层确认有效成员资格并加载详情。
        return Result.ok(gatheringService.getGathering(gatheringId, userId));
    }

    /** 创建者全量保存名称与介绍，介绍必须提交，null 表示清空。 */
    @Operation(summary = "全量修改同行信息", operationId = "09_update_gathering")
    @ApiResponse(responseCode = "200", description = "修改成功，返回最新同行信息")
    @ApiResponse(responseCode = "400", description = "同行 ID、名称或介绍无效，或缺少必传字段")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是创建者")
    @ApiResponse(responseCode = "404", description = "同行不存在或已解散")
    @PutMapping(value = "/{gatheringId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<GatheringResponse> updateGathering(
            @Parameter(description = "同行 UUIDv7") @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @Valid @RequestBody UpdateGatheringRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 业务层校验创建者权限，只更新基本信息并保留其他设置。
        return Result.ok(gatheringService.updateGathering(gatheringId, request, userId));
    }

    /** 创建者为创建后 10 分钟内的同行开启面对面入口。 */
    @Operation(summary = "开启面对面短码加入", operationId = "04_open_face_to_face",
            description = "创建者提交四位数字短码及发起位置。每个同行只允许成功开启一次，短码永久保存，邀请在同行创建后 10 分钟截止。")
    @ApiResponse(responseCode = "200", description = "返回首次登记的短码与截止时间")
    @ApiResponse(responseCode = "400", description = "短码或坐标无效、邀请关闭或创建窗口已结束")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是创建者")
    @ApiResponse(responseCode = "404", description = "同行不存在或已解散")
    @ApiResponse(responseCode = "409", description = "该同行已开启过面对面邀请")
    @PostMapping(value = "/{gatheringId}/face-to-face", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<FaceToFaceOpenResponse> openFaceToFace(
            @PathVariable @UUIDv7(checkTimestamp = false) UUID gatheringId,
            @Valid @RequestBody OpenFaceToFaceRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 业务层首次登记数据库短码，并将发起位置写入 GEO。
        return Result.ok(faceToFaceService.open(gatheringId, request, userId));
    }

    /** 查找附近同码的有效同行，零匹配返回空列表，多个匹配全部展示。 */
    @Operation(summary = "查找附近面对面同行", operationId = "05_preview_face_to_face",
            description = "按四位短码和当前位置查找 100 米内尚未过期、开放邀请的同行，按距离从近到远返回候选列表。")
    @ApiResponse(responseCode = "200", description = "返回候选列表，零匹配为 []")
    @ApiResponse(responseCode = "400", description = "短码或坐标无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @GetMapping("/face-to-face/join-preview")
    public Result<List<FaceToFaceCandidateResponse>> previewFaceToFace(
            @Valid @ModelAttribute @ParameterObject FaceToFacePreviewRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 查询阶段只返回附近候选，成员关系在确认加入时建立。
        return Result.ok(faceToFaceService.preview(request, userId));
    }

    /** 用户选择目标后，凭同行 ID 和短码确认加入。 */
    @Operation(summary = "通过面对面短码确认加入", operationId = "06_join_face_to_face",
            description = "提交同行 ID 和短码，按数据库记录校验短码、创建后 10 分钟有效期及邀请开关，再建立或恢复成员关系。")
    @ApiResponse(responseCode = "200", description = "加入成功或已是有效成员，返回同行信息")
    @ApiResponse(responseCode = "400", description = "同行 ID 或短码无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "404", description = "邀请过期、短码不匹配或目标同行不可加入")
    @PostMapping(value = "/face-to-face/join", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<GatheringResponse> joinFaceToFace(
            @Valid @RequestBody JoinFaceToFaceRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 按用户选中的同行确认加入，复用现有成员维护规则。
        return Result.ok(gatheringService.joinFromFaceToFace(request, userId));
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

    // TODO 缺少重置邀请码的业务
}

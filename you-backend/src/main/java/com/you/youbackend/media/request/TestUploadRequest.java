package com.you.youbackend.media.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.URL;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通过预签名地址代传文件的测试表单。
 */
@Data
@Schema(description = "预签名上传测试表单")
public class TestUploadRequest {

    /**
     * 申请上传接口返回的完整 PUT 地址。
     */
    @NotBlank(message = "请提供预签名上传地址")
    @URL(message = "上传地址格式不正确")
    @Schema(description = "申请上传接口返回的完整 URL", format = "uri")
    private String url;

    /**
     * 待上传的文件，文件部分的 Content-Type 应与申请时一致。
     */
    @NotNull(message = "请选择上传文件")
    @Schema(description = "待上传文件，其 Content-Type 与申请时一致", type = "string", format = "binary")
    private MultipartFile file;
}

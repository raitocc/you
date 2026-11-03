package com.you.youbackend;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test")
@Tag(name = "测试接口")
@Log4j2
public class TestController {
    @Operation(summary = "Get 方法的打招呼", description = "根据姓名返回问候语")
    @ApiResponse(responseCode = "200", description = "请求成功")
    @GetMapping("/get")
    public String hello(
            @Parameter(description = "姓名", example = "小明")
            @RequestParam(name = "name", defaultValue = "World")
            String name) {
        log.info("get hello, name={}", name);
        return "Hello, " + name;
    }

    @Operation(summary = "Post 方法的打招呼", description = "根据姓名返回问候语")
    @PostMapping("/post")
    public String postHello(
            @RequestBody
            String name) {
        log.info("post hello, name={}", name);
        return "Hello, " + name;
    }

}

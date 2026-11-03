# Scalar：从接口接入到在线调试

跟随本教程，为一个问候接口生成 OpenAPI 文档，并在 Scalar 页面发送 GET、POST 请求。

示例环境：Java 17、Spring Boot 4.1、Spring MVC。包名统一为 `com.example.demo`。已有可启动的 Spring MVC 应用即可开始。

第一次接入，顺序阅读第 1～5 节；修改路径、分组和认证，查第 6 节；遇到访问问题，查第 7 节。

## 1. 认识 Scalar 与 OpenAPI 文档

一份接口文档从 Controller 生成，再由 Scalar 展示：

```text
Spring MVC 接口 → springdoc-openapi → OpenAPI 文档 → Scalar 页面
```

| 名称 | 本文含义 |
|---|---|
| Spring MVC 接口 | 实际接收请求、执行 Java 方法并返回响应的接口 |
| OpenAPI 文档 | 描述路径、参数、请求体和响应结构的 JSON 数据 |
| Scalar 页面 | 阅读 OpenAPI 文档、填写参数和发送请求的网页 |

MVC 注解定义接口的运行方式，OpenAPI 注解补充接口说明。参数校验和认证由服务端执行；这也是后文区分运行配置与文档配置的依据。

## 2. 接入 Scalar 并打开页面

### 2.1 添加依赖

在 `pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-scalar</artifactId>
    <version>3.2.0</version>
</dependency>
```

这个 starter 同时提供 OpenAPI 文档生成和 Scalar 页面。重新加载 Maven，启动 Spring Boot 应用。

### 2.2 确认两个地址

在默认端口 `8080` 下打开：

| 地址 | 预期结果 |
|---|---|
| `http://localhost:8080/v3/api-docs` | OpenAPI 文档，JSON 中包含 `openapi`、`paths` 等字段 |
| `http://localhost:8080/scalar` | Scalar 页面，可查看应用中的接口 |

接下来创建一个接口，让它出现在 Scalar 页面中。其他 Boot 版本的依赖选择集中在第 6.5 节。[接入说明](https://springdoc.org/)

## 3. 编写第一个带说明的接口

创建 `src/main/java/com/example/demo/web/GreetingController.java`。启动类所在包为 `com.example.demo`，Spring 会扫描到这个 Controller。

```java
package com.example.demo.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "问候", description = "接收姓名并返回问候语")
@RestController
@RequestMapping("/api/greetings")
public class GreetingController {

    @Operation(summary = "通过查询参数问候")
    @GetMapping
    public GreetingResponse getGreeting(
            @Parameter(description = "姓名", example = "小明")
            @RequestParam(name = "name", defaultValue = "World") String name) {
        return new GreetingResponse("Hello, " + name);
    }

    @Operation(summary = "通过 JSON 提交姓名")
    @PostMapping
    public GreetingResponse postGreeting(@RequestBody GreetingRequest request) {
        return new GreetingResponse("Hello, " + request.name());
    }

    @Schema(description = "问候请求")
    public record GreetingRequest(
            @Schema(description = "姓名", example = "小明") String name
    ) {}

    @Schema(description = "问候结果")
    public record GreetingResponse(
            @Schema(description = "问候语", example = "Hello, 小明") String message
    ) {}
}
```

重启应用，刷新 Scalar 页面。预期看到“问候”分组，以及两个接口：

| 接口 | 参数位置 | 返回结构 |
|---|---|---|
| `GET /api/greetings` | 查询参数 `name` | 含 `message` 的 JSON 对象 |
| `POST /api/greetings` | JSON 请求体中的 `name` | 含 `message` 的 JSON 对象 |

代码使用 record 定义数据传输对象（DTO）。`GreetingRequest` 描述输入结构，`GreetingResponse` 描述输出结构。

## 4. 按场景编写 OpenAPI 注解

本节依次解释示例中的四种注解。每种注解对应 Scalar 页面上的一处信息。

### 4.1 用 @Tag 组织一组接口

把 `@Tag` 放在 Controller 类上，为这一组接口起名：

```java
@Tag(name = "问候", description = "接收姓名并返回问候语")
```

`name` 对应分组名称；`description` 介绍这组接口的用途。同一业务组的接口可以使用相同名称。

### 4.2 用 @Operation 说明一个接口

把 `@Operation` 放在接口方法上：

```java
@Operation(
        summary = "通过查询参数问候",
        description = "接收姓名并返回问候语，姓名默认值为 World。"
)
```

`summary` 适合一句话概括动作；`description` 说明输入、处理规则或结果。读者先通过摘要找到接口，再展开详细说明。

### 4.3 用 @Parameter 解释路径或查询参数

GET 示例的姓名来自查询参数。两种注解分别提供绑定规则和文档信息：

```java
@Parameter(description = "姓名", example = "小明")
@RequestParam(name = "name", defaultValue = "World") String name
```

`@RequestParam` 确定参数名和默认值；`@Parameter` 提供说明和示例值。Scalar 页面据此显示 `name` 输入项，并帮助读者填写“小明”。

路径参数 `@PathVariable` 也可以用相同方式补充说明。

### 4.4 用 @Schema 解释请求和响应字段

数据模型（Schema）描述 JSON 对象的字段。把注解放在 DTO 或其字段上：

```java
@Schema(description = "问候请求")
public record GreetingRequest(
        @Schema(description = "姓名", example = "小明") String name
) {}
```

类上的说明帮助读者理解对象用途；属性上的说明解释具体字段。Scalar 页面会展示对应结构和示例值（Example）。字符串、数字、集合等类型由 Java 类型推断。

字段还有格式要求时，可补充 `format`，例如邮箱字段使用 `format = "email"`。[Schema 注解参考](https://docs.swagger.io/swagger-core/v2.2.38/apidocs/io/swagger/v3/oas/annotations/media/Schema.html)

## 5. 在 Scalar 页面验证 GET 和 POST

### 5.1 发送 GET 请求

1. 打开 Scalar 页面，展开“问候”分组中的 `GET /api/greetings`。
2. 点击发送请求（`Test Request`），在查询参数 `name` 中填写“小明”。
3. 发送请求，在响应区域查看 HTTP 状态和响应体。

预期状态为 `200`，响应体为：

```json
{"message":"Hello, 小明"}
```

### 5.2 发送 POST 请求

1. 选择 `POST /api/greetings`，打开请求编辑区域。
2. 使用 `application/json` 请求体，填写：

   ```json
   {"name":"小明"}
   ```

3. 发送请求，确认状态为 `200`，响应体与 GET 示例一致。

页面中的示例值帮助填写请求；响应区域展示服务端实际返回的结果。完成这两次请求后，接口接入、文档生成和在线调试就串起来了。

## 6. 调整路径、分组与认证

以下配置按任务独立查阅。示例中的路径延续第 3 节的 `/api/greetings`。

### 6.1 修改 Scalar 页面的地址和标题

在 `application.yaml` 中增加：

```yaml
scalar:
  path: /docs
  page-title: 示例 API
```

重启后访问 `http://localhost:8080/docs`。OpenAPI 文档仍由 `/v3/api-docs` 提供。

### 6.2 修改 OpenAPI 文档地址

将生成地址和读取地址设为同一路径：

```yaml
springdoc:
  api-docs:
    path: /openapi

scalar:
  url: /openapi
```

此时访问 `/openapi` 查看 OpenAPI 文档，访问 `/scalar` 查看 Scalar 页面。[路径配置参考](https://springdoc.org/properties.html)

### 6.3 按路径生成一份接口分组文档

创建 `src/main/java/com/example/demo/config/ApiGroupConfig.java`：

```java
package com.example.demo.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiGroupConfig {
    @Bean
    public GroupedOpenApi greetingApi() {
        return GroupedOpenApi.builder()
                .group("greetings")
                .pathsToMatch("/api/greetings", "/api/greetings/**")
                .build();
    }
}
```

这份 OpenAPI 文档的地址为 `/v3/api-docs/greetings`。让 Scalar 页面读取它：

```yaml
scalar:
  url: /v3/api-docs/greetings
```

`@Tag` 用于文档内的接口分组；`GroupedOpenApi` 用于生成可单独访问的 OpenAPI 文档。

### 6.4 为在线请求配置 Bearer 认证

已有令牌认证的接口，可以把认证方式描述给 Scalar 页面。创建 `src/main/java/com/example/demo/config/ApiAuthConfig.java`：

```java
package com.example.demo.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer")
public class ApiAuthConfig {
}
```

在受保护的 Controller 或方法上添加：

```java
// import io.swagger.v3.oas.annotations.security.SecurityRequirement;
@SecurityRequirement(name = "bearerAuth")
```

随后在 Scalar 页面的认证区域填入令牌（Token）。发送请求时，检查请求头是否包含 `Authorization: Bearer <令牌>`。

### 6.5 为其他 Spring Boot 版本选择依赖

| Spring Boot | springdoc 版本范围 |
|---|---|
| 4.1.x | 3.1.x～3.2.x |
| 4.0.x | 3.0.x |
| 3.x | 2.x，具体组合查对应版本兼容表 |

Boot 3 可以使用 Scalar 官方的独立集成方式：引入匹配版本的 `springdoc-openapi-starter-webmvc-api` 和 `com.scalar.maven:scalar-webmvc`，然后设置 `scalar.enabled=true`、`scalar.url=/v3/api-docs`。

依赖版本与完整配置见 [Scalar 官方接入说明](https://scalar.com/docs-for/spring-boot) 和 [springdoc 兼容表](https://springdoc.org/)。

### 6.6 补充响应说明与隐藏接口

在方法上使用 `@ApiResponse(responseCode = "200", description = "返回问候结果")` 描述响应；使用 `@Hidden` 隐藏内部接口。两者的导入分别为：

```java
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
```

实际 HTTP 状态由 `ResponseEntity` 或 `@ResponseStatus` 控制；接口访问范围由服务端认证和权限规则控制。

## 7. 排查页面访问和请求错误

| 现象 | 检查与处理 |
|---|---|
| Scalar 页面返回 404 | 确认 starter 已加载，核对 `scalar.path`、端口和应用路径前缀 |
| OpenAPI 文档地址返回 404 | 核对 `springdoc.api-docs.path`，确认文档生成已启用 |
| Scalar 页面显示演示接口 | 把 `scalar.url` 指向应用实际生成的 OpenAPI 文档 |
| 示例接口未展示 | 确认 Controller 位于扫描范围内，检查分组路径与 `@Hidden` |
| 在线请求返回 400 或 415 | 对照第 5 节核对参数位置、JSON 结构和 Content-Type |
| 在线请求返回 401 或 403 | 检查认证区域的令牌和实际发出的 Authorization 请求头 |
| 修改页面样式或路径后保持原样 | 核对使用的是 `scalar.*` 属性，重启应用并刷新页面 |

应用配置了 `server.servlet.context-path` 时，访问地址统一带上该前缀，例如 `/app/scalar` 和 `/app/v3/api-docs`。

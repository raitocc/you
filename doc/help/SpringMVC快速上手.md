# Spring MVC：编写和调用 HTTP 接口

跟随本教程，创建一个接收姓名、返回 JSON 的接口。随后按参数来源学习路径参数、查询参数和请求体，再扩展校验、上传与异常响应。

示例使用 Java 17、Spring Boot，包名为 `com.example.demo`。第一次运行看第 1～3 节，编写其他接口查第 4～8 节，定位请求错误查第 9 节。

## 1. 理解一次请求如何到达 Java 方法

```text
HTTP 请求 → Spring MVC 匹配路径与方法 → Controller 接收参数 → 返回 HTTP 响应
```

Controller 负责接口输入和输出；Service 负责业务处理。下面先用一个完整 Controller 学习 HTTP 请求与 Java 方法之间的对应关系。

## 2. 添加 Web 依赖

在已有的 Spring Boot 项目中添加对应版本的 Web starter。下面以 Boot 4 为例：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
```

Boot 3 使用 `spring-boot-starter-web`。依赖版本由 Spring Boot 管理。

重新加载 Maven。Controller 放在启动类所在包的子包中，例如启动类在 `com.example.demo`，Controller 放在 `com.example.demo.web`。Spring Boot 会完成 Web 服务和组件扫描配置。

## 3. 创建并调用第一个接口

### 3.1 编写 Controller 与输入输出对象

创建 `src/main/java/com/example/demo/web/GreetingController.java`：

```java
package com.example.demo.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/greetings")
public class GreetingController {

    @GetMapping
    public GreetingResponse getGreeting(
            @RequestParam(name = "name", defaultValue = "World") String name) {
        return new GreetingResponse("Hello, " + name);
    }

    @PostMapping
    public GreetingResponse postGreeting(@RequestBody GreetingRequest request) {
        return new GreetingResponse("Hello, " + request.name());
    }

    public record GreetingRequest(String name) {}
    public record GreetingResponse(String message) {}
}
```

`GreetingRequest` 和 `GreetingResponse` 是数据传输对象（DTO），分别承载输入与输出。示例用 record 定义它们，普通 Java 类也适用。

### 3.2 调用 GET，传入查询参数

启动应用，在浏览器打开 `http://localhost:8080/api/greetings?name=小明`。

预期响应：

```json
{"message":"Hello, 小明"}
```

请求中的 `name=小明` 由 `@RequestParam` 绑定给方法参数。省略 `name` 时，`defaultValue` 提供 `World`。

### 3.3 调用 POST，传入 JSON 请求体

在接口调试工具中发送：

```http
POST /api/greetings HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{"name":"小明"}
```

Windows PowerShell 中可执行：

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/greetings' -ContentType 'application/json; charset=utf-8' -Body '{"name":"小明"}'
```

预期返回相同的问候结果。`@RequestBody` 把 JSON 转成 `GreetingRequest`，返回的 `GreetingResponse` 转成 JSON。

到这里，接口路径、参数绑定和 JSON 响应已经形成完整流程。

## 4. 根据参数来源选择注解

后续方法都可以加入前面的 `GreetingController`。类上的公共路径为 `/api/greetings`，方法路径会拼接在它后面。

### 4.1 路径中的 ID：@PathVariable

导入 `org.springframework.web.bind.annotation.PathVariable`，增加方法：

```java
@GetMapping("/{id}")
public GreetingResponse getById(@PathVariable("id") Long id) {
    return new GreetingResponse("问候记录 " + id);
}
```

调用 `GET /api/greetings/123`，得到 `{"message":"问候记录 123"}`。这里演示将路径中的 `123` 转成 Long。

### 4.2 可选查询条件：@RequestParam

增加一个支持关键词与页码的接口：

```java
@GetMapping("/search")
public SearchQuery search(
        @RequestParam(name = "keyword", required = false) String keyword,
        @RequestParam(name = "page", defaultValue = "1") int page) {
    return new SearchQuery(keyword, page);
}

public record SearchQuery(String keyword, int page) {}
```

调用 `GET /api/greetings/search?keyword=java&page=2`，返回 `{"keyword":"java","page":2}`。

`@RequestParam` 默认要求参数存在。`required = false` 接受省略的参数，`defaultValue` 提供默认值。可空数字参数使用 `Integer` 等包装类型。[查询参数绑定](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestparam.html)

查询条件较多时，可用 `@ModelAttribute` 一次绑定到对象：

```java
// import org.springframework.web.bind.annotation.ModelAttribute;
@GetMapping("/filter")
public FilterQuery filter(@ModelAttribute FilterQuery query) {
    return query;
}

public record FilterQuery(String keyword, Integer page, Integer size) {}
```

调用地址为 `/api/greetings/filter?keyword=java&page=1&size=20`，各查询参数按属性名称绑定。

### 4.3 JSON 对象：@RequestBody

第 3 节的 POST 使用一个 DTO 接收完整 JSON 对象。需要更多字段时，将字段加入 DTO：

```java
public record GreetingRequest(String name, String language) {}
```

对应请求体为 `{"name":"小明","language":"zh"}`，代码通过 `request.name()`、`request.language()` 读取。

使用 `@RequestBody String body` 时，目标类型是原始文本；要按属性读取 JSON，使用有明确字段的 DTO。[请求体转换](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestbody.html)

### 4.4 请求头：@RequestHeader

```java
// import org.springframework.web.bind.annotation.RequestHeader;
@GetMapping("/request-id")
public GreetingResponse requestId(
        @RequestHeader(name = "X-Request-Id", defaultValue = "demo") String requestId) {
    return new GreetingResponse(requestId);
}
```

调用 `GET /api/greetings/request-id` 并设置请求头 `X-Request-Id: abc123`，返回 `{"message":"abc123"}`。

### 4.5 按请求格式快速选择绑定方式

| 请求数据 | 常用注解 | 例子 |
|---|---|---|
| 路径片段 | `@PathVariable` | `/greetings/123` |
| 查询参数 | `@RequestParam` | `?name=小明` |
| 多个查询或表单参数 | `@ModelAttribute` | `keyword=java&page=1` |
| JSON 请求体 | `@RequestBody` | `{"name":"小明"}` |
| 请求头 | `@RequestHeader` | `X-Request-Id: abc123` |
| 文件表单 | `MultipartFile`，见第 7 节 | multipart 中的 `file` 字段 |

`application/json` 对应 JSON；`application/x-www-form-urlencoded` 对应普通表单；`multipart/form-data` 对应文件表单。Content-Type 帮助 Spring 选择相应的解析方式。

## 5. 返回对象、状态码和响应头

在 `@RestController` 中返回对象或 List，Spring 通常将其写成 JSON；返回 String，通常将其写成文本。第 3 节使用对象表达 `{"message":"..."}`。

需要指定 HTTP 状态时，使用 `ResponseEntity`。导入 `org.springframework.http.ResponseEntity` 后增加：

```java
@GetMapping("/status")
public ResponseEntity<GreetingResponse> status() {
    return ResponseEntity.ok(new GreetingResponse("正常"));
}
```

调用 `GET /api/greetings/status`，得到状态 `200` 和 `{"message":"正常"}`。[响应状态与响应头](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/responseentity.html)

常用返回方式：

| 写法 | HTTP 状态与内容 |
|---|---|
| `ResponseEntity.ok(body)` | 200，带响应体 |
| `ResponseEntity.status(201).body(body)` | 201，带响应体 |
| `ResponseEntity.noContent().build()` | 204，空响应体 |
| `ResponseEntity.notFound().build()` | 404 |
| `ResponseEntity.badRequest().body(error)` | 400，带错误对象 |

HTTP 方法也表达操作意图：GET 常用于查询，POST 用于提交或创建，PUT 用于完整更新，PATCH 用于局部更新，DELETE 用于删除。分别使用对应的 `@GetMapping`、`@PostMapping`、`@PutMapping`、`@PatchMapping`、`@DeleteMapping`。[请求映射参考](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html)

## 6. 校验 JSON 中的字段

先添加校验依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

为第 3 节的请求对象标注规则，并在接收处增加 `@Valid`：

```java
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GreetingRequest(
        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名最多 64 个字符") String name
) {}

@PostMapping
public GreetingResponse postGreeting(@Valid @RequestBody GreetingRequest request) {
    return new GreetingResponse("Hello, " + request.name());
}
```

用这个 DTO 和方法替换原来的对应定义。发送 `{"name":"小明"}` 会正常返回；发送 `{"name":""}` 会触发校验并返回 400。

| 注解 | 校验内容 |
|---|---|
| `@NotNull` | 值必须存在 |
| `@NotEmpty` | 值存在且字符串或集合有内容 |
| `@NotBlank` | 字符串含有非空白字符 |
| `@Size` | 字符串长度或集合大小符合范围 |

`@Size` 可配合必填注解使用。账号唯一性、对象权限等业务规则放在 Service 层处理。

## 7. 接收上传文件

增加导入与方法：

```java
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

@PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public UploadResponse upload(@RequestParam("file") MultipartFile file) {
    return new UploadResponse(file.getOriginalFilename(), file.getSize());
}

public record UploadResponse(String filename, long size) {}
```

向 `POST /api/greetings/files` 发送 multipart 表单，将文件字段命名为 `file`。接口返回文件名和字节数；文件保存由后续业务代码处理。

调整文件和请求的大小上限：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 30MB
```

文件与 JSON 混合提交时，可用 `@RequestPart` 分别接收各部分。[上传参数绑定](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html)

## 8. 将异常转换为 JSON 响应

创建 `src/main/java/com/example/demo/web/ApiExceptionHandler.java`：

```java
package com.example.demo.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    public record ErrorResponse(String message) {}
}
```

Controller 处理中抛出 `IllegalArgumentException("姓名格式错误")` 时，这个处理器返回 400 和 `{"message":"姓名格式错误"}`。其他异常类型可以各自增加处理方法，形成统一的错误响应。[异常处理说明](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html)

## 9. 根据 HTTP 状态定位问题

| 现象 | 检查与处理 |
|---|---|
| 404 | 核对公共路径、方法路径、扫描包与应用路径前缀 |
| 405 | 使用映射要求的 HTTP 方法；浏览器地址栏发送 GET |
| 400 | 核对必填参数、数值格式、JSON 结构和校验规则 |
| 415 | 让 Content-Type 与 JSON、普通表单或文件表单的格式匹配 |
| JSON 属性值为 null | 核对 DTO 属性名与请求中的字段名 |
| 文件上传失败 | 核对 multipart 字段名和上传大小限制 |
| 浏览器提示跨域 | 核对请求来源，为对应来源设置 CORS 规则 |

这些检查分别对应接口的路径、方法、输入格式和输出行为；从请求本身入手，通常能快速定位问题。

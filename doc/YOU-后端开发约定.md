# YOU 后端开发约定

队友新增接口时，以现有媒体上传接口为例，沿着“定义请求 → 编写业务 → 接入 Controller → 返回结果 → 验证接口”完成开发。本文约定代码组织与接口表达，业务字段和权限规则由具体需求确定。

## 1. 当前可复用的代码

| 内容 | 现有实现 |
| --- | --- |
| 请求模型与校验 | [CreateUploadRequest](../you-backend/src/main/java/com/you/youbackend/media/request/CreateUploadRequest.java) |
| 接口与 OpenAPI 注解 | [MediaController](../you-backend/src/main/java/com/you/youbackend/media/controller/MediaController.java) |
| 业务处理与构造器注入 | [MediaService](../you-backend/src/main/java/com/you/youbackend/media/service/MediaService.java) |
| 响应模型 | [CreateUploadResponse](../you-backend/src/main/java/com/you/youbackend/media/response/CreateUploadResponse.java) |
| 统一响应 | [Result](../you-backend/src/main/java/com/you/youbackend/common/response/Result.java) |
| 业务异常 | [BusinessException](../you-backend/src/main/java/com/you/youbackend/common/exception/BusinessException.java) |
| 全局异常处理 | [GlobalExceptionHandler](../you-backend/src/main/java/com/you/youbackend/common/exception/GlobalExceptionHandler.java) |

当前媒体模块已实现申请预签名 PUT 地址，以及接收文件并转发至 RustFS 的测试接口。`MediaFile`、`MediaFileMapper` 目前是占位类，上传确认及文件入库后续实现。异常处理的覆盖范围见第 6 节。

## 2. 按业务组织目录

当前应用根包为 `com.you.youbackend`，业务包放在这个包下面，供启动类自动扫描。

```text
com.you.youbackend
├── YouBackendApplication.java
├── S3Config.java
├── common
│   ├── response/Result.java
│   └── exception
│       ├── BusinessException.java
│       └── GlobalExceptionHandler.java
└── media
    ├── controller/MediaController.java
    ├── service/MediaService.java
    ├── request
    │   ├── CreateUploadRequest.java
    │   └── TestUploadRequest.java
    ├── response
    │   ├── CreateUploadResponse.java
    │   └── TestUploadResponse.java
    ├── entity/MediaFile.java
    └── mapper/MediaFileMapper.java
```

其他业务包使用相同结构，随功能创建实际需要的文件。Service 先使用具体的 `@Service` 类；多个实现确有共同契约时再提取接口。MyBatis-Plus 的 `IService` 用于通用数据库操作，是否使用由业务决定。

## 3. 定义 Request 和 Response

接口入参使用 `动作 + 对象 + Request`，响应数据使用对应的 `Response`，例如 `CreateUploadRequest`、`CreateUploadResponse`。请求、响应的固定字段通过明确的 Java 类型表达；Map 用于键名本身由业务动态决定的数据，例如请求头集合。

Request 和 Response 的类、字段添加 `@Schema`，写清含义、单位、示例和特殊值语义。例如“文件大小”应说明单位为字节；更新资料时，可空字段应说明传 `null` 的含义。字段声明为 `private`，可通过 Lombok 生成访问方法；简单的数据载体也可以使用 `record`。

基础格式校验放在 Request 中：

```java
@Schema(description = "文件字节数，最大 10 MiB", example = "1000000")
@NotNull(message = "请提供文件大小")
@Positive(message = "文件大小必须大于 0")
@Max(value = MAX_UPLOAD_BYTES, message = "文件大小最多为 {value} 字节")
private Long byteSize;
```

必填数字使用 `Long`、`Integer` 等包装类型配合 `@NotNull`，分别表达字段缺失和数值无效。字符串通过 `@NotBlank`、`@Size` 等注解校验。枚举直接声明为枚举类型；JSON 使用不同于枚举名称的字符串时，参考现有 `MimeType` 的 `@JsonValue` 写法。

JSON 请求使用 `@Valid @RequestBody`；包含文件的表单使用 `@Valid @ModelAttribute`，并在映射上声明 `consumes = MediaType.MULTIPART_FORM_DATA_VALUE`。测试上传接口的 `TestUploadRequest` 演示了字符串和 `MultipartFile` 一起接收的写法。

## 4. 编写 Service 和 Mapper

Service 负责业务规则、流程组织和外部组件调用，例如文件归属检查、生成对象键、调用签名器。依赖通过构造器注入，字段使用 `final`；全部依赖均为普通 Bean 时，可以使用 `@RequiredArgsConstructor`。参数包含 `@Value` 配置注入时，参考现有 `MediaService` 的显式构造器。

Service 接收 Request 或明确的业务参数，返回 Response 或业务结果；由 Controller 包装 `Result`。方法内按业务步骤组织，独立步骤提取为有意义的方法。变量使用 `request`、`storageKey`、`mediaFile` 等能表达含义的名称，条件分支使用大括号。

Entity 对应数据库记录，接口输出由 Response 表达。发生数据库读写时再接入 Mapper；MyBatis-Plus 的基础形式是 `@Mapper` 接口继承 `BaseMapper<MediaFile>`。SQL 与持久化操作归 Mapper，业务决策归 Service。复杂 SQL 使用便于阅读和维护的排版或 Mapper XML。

一个业务操作包含多次需要共同提交的数据修改时，事务边界放在 Service。表结构、UUIDv7 的 `BINARY(16)` 映射、UTC 审计时间和 `deleted_at` 逻辑删除，按[数据库设计](YOU-数据库设计.md)实现；改表通过新增 Flyway 迁移交付。

## 5. 接入 Controller 和接口文档

Controller 接收请求、触发校验、委托 Service，并将结果转换为接口响应。普通 JSON 接口沿用现有写法：

```java
@Operation(
        summary = "申请图片上传地址",
        description = "返回有效期为 10 分钟的 PUT 地址；上传时 Content-Type 与申请值一致。")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "上传地址生成成功"),
        @ApiResponse(responseCode = "400", description = "请求参数或图片类型无效")
})
@PostMapping
public Result<CreateUploadResponse> createUpload(
        @Valid @RequestBody CreateUploadRequest request) {
    return Result.ok(mediaService.createUpload(request));
}
```

Controller 类添加 `@RestController`、`@RequestMapping` 和 `@Tag`。接口路径、HTTP 方法及字段含义与接口设计保持一致，变更时同步接口文档和前端约定。

| 注解 | 放置位置 | 内容 |
| --- | --- | --- |
| `@Tag` | Controller 类 | 模块名称与用途 |
| `@Operation` | 接口方法 | 接口名称、调用条件和必要的使用说明 |
| `@ApiResponse` / `@ApiResponses` | 接口方法 | 实际 HTTP 状态码及业务含义 |
| `@Schema` | Request、Response 及字段 | 模型含义、字段说明、示例 |
| `@Valid` | 请求对象参数 | 触发对象字段校验 |

JavaDoc 面向维护代码的人，说明类的职责、公开业务方法的输入和结果；行内注释解释业务原因、约定和特殊处理。OpenAPI 注解面向接口调用者，说明如何提交和使用响应。Service、Mapper 和配置类通过 JavaDoc 说明职责，接口文档注解集中在 Controller 与请求、响应模型。

## 6. 统一返回 Result，集中处理异常

普通 JSON 成功响应统一返回 `Result<T>`：

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": { "url": "上传地址" }
}
```

失败响应使用同样的字段，`data` 为 `null`。字段名统一使用 `message`：

```json
{
  "code": "INVALID_ARGUMENT",
  "message": "文件大小必须大于 0",
  "data": null
}
```

HTTP 状态码和业务 `code` 分别表达协议结果与具体原因。目前约定如下：

| 情况 | HTTP 状态码 | 业务 code | 生成位置 |
| --- | --- | --- | --- |
| 普通成功 | 200 | `SUCCESS` | Controller 的 `Result.ok(...)` |
| 字段校验失败 | 400 | `INVALID_ARGUMENT` | 全局异常处理器 |
| JSON、字段类型或枚举解析失败 | 400 | `INVALID_REQUEST_BODY` | 全局异常处理器 |
| 业务异常（BusinessException） | 异常携带的状态 | 异常携带的 code，例如 `UPLOAD_FAILED` | 全局异常处理器 |

`Result.fail(...)` 只创建响应体，HTTP 状态由 `@ResponseStatus` 或 `ResponseEntity` 设置。普通 200 响应直接返回 `Result<T>`；需要设置状态或响应头时，使用 `ResponseEntity<Result<T>>`。文件二进制、重定向等响应按其 HTTP 用途返回。

### 在 Service 中抛出业务异常

字段格式由 Request 校验；依赖业务判断的失败由 Service 抛出 `BusinessException`。例如，测试上传拿到文件后检查其内容：

```java
if (file.isEmpty()) {
    throw new BusinessException(
            HttpStatus.BAD_REQUEST,
            "INVALID_ARGUMENT",
            "请选择有内容的文件");
}
```

构造器的三个参数分别是 HTTP 状态 `status`、供客户端判断的业务错误码 `code`、供用户阅读的提示 `message`。例如上述异常返回 HTTP 400，响应体为 `{"code":"INVALID_ARGUMENT","message":"请选择有内容的文件","data":null}`。同一种失败保持稳定的错误码，提示文字可以调整。

外部组件调用失败时，用带 `cause` 的构造器保留原始异常；日志按第 7 节的约定记录：

```java
throw new BusinessException(
        HttpStatus.BAD_GATEWAY,
        "UPLOAD_FAILED",
        "RustFS 上传失败",
        exception);
```

这里返回 HTTP 502 和 `UPLOAD_FAILED`；原始异常保留在异常链中。现有 `MediaService` 还会在提示里附带 RustFS 的错误详情，方便联调。业务条件校验失败使用三个参数的构造器即可。

### 全局处理器生成失败响应

`GlobalExceptionHandler` 的 `@RestControllerAdvice` 统一处理 Controller 调用过程中向外抛出的异常。业务异常对应以下处理方法：

```java
@ExceptionHandler(BusinessException.class)
public ResponseEntity<Result<Void>> handleBusiness(BusinessException exception) {
    return ResponseEntity.status(exception.getStatus())
            .body(Result.fail(exception.getCode(), exception.getMessage()));
}
```

调用链为：Service 抛出异常 → 全局处理器设置 HTTP 状态并创建 `Result` → Spring MVC 输出 JSON。Controller 继续使用第 5 节的正常返回写法。Request 校验和 JSON 解析由 Spring 抛出对应异常，分别进入现有的另外两个处理方法。

新增业务失败时，通常在 Service 中选择状态、确定错误码并抛出 `BusinessException` 即可；Controller 的 `@ApiResponse` 同步描述该 HTTP 状态及含义。OpenAPI 注解负责文档展示，实际 HTTP 状态由处理器设置。

### 当前实现范围

当前已统一字段校验失败、请求体解析失败和显式抛出的业务异常。文件超过 multipart 大小上限等其他框架异常，以及未知系统异常，目前仍走 Spring Boot 默认错误响应；出现对应需求时补充全局处理方法。

媒体上传中，`byteSize` 校验的是申请时声明的大小，当前签名绑定了 `Content-Type`，实际直传文件的大小核验仍待实现。测试上传接口另受 `spring.servlet.multipart` 的大小配置限制。上传确认和媒体文件入库也属于后续业务实现。

## 7. 记录关键业务日志

使用现有 Log4j2，在需要记录日志的类上添加 `@Log4j2`，通过 `log.info(...)` 等方法调用。日志沿用 Spring Boot / Log4j2 的默认格式和 INFO 级别，时间、级别、线程和类名由框架输出。排查问题时，通过 `logging.level.包名: DEBUG` 临时开启对应包的详细日志。

### 选择级别和记录位置

| 级别 | 使用场景 | 示例 |
| --- | --- | --- |
| `INFO` | 关键业务结果、正常业务拒绝 | 注册成功、用户名已存在、上传地址已生成、测试上传成功 |
| `WARN` | 值得关注的异常情况 | 登录失败、权限拒绝、外部调用进入重试 |
| `ERROR` | 操作失败且需要定位的故障 | 存储服务上传失败、数据库连接失败、未预期异常 |
| `DEBUG` | 调试过程和普通参数拒绝原因 | 测试上传地址不属于当前桶、文件为空 |

Service 在关键步骤完成后记录结果；Controller 保持接收参数、调用 Service 和返回响应的流程。Mapper 的 SQL 调试使用框架日志。高频的普通查询按排查需要记录 DEBUG，日常保留能说明重要业务结果的信息。

成功日志对应实际完成的操作：生成签名地址记录“上传地址已生成”，完成 PUT 后记录“测试上传成功”。当前注册只有一次插入，在插入成功后记录；涉及事务时，提交成功再记录最终成功，事务内的日志仅表示当时的执行进度。

### 写清事件和关键字段

消息使用“事件 + 关键字段”，通过 `{}` 传参。对象统一使用 `userId`、`storageKey` 等明确的标识，文件大小区分申请时声明的大小与实际接收大小。例如：

```java
log.info("注册成功，userId={}", userId);

log.info("注册被拒绝，reason=用户名已存在，username={}", username);

log.info("上传地址已生成，storageKey={}，mimeType={}，declaredByteSize={}",
        storageKey, request.getMimeType(), request.getByteSize());

log.info("测试上传成功，statusCode={}，byteSize={}",
        response.getStatusCode().value(), file.getSize());
```

同一次故障选择一个位置记录：Service 已记录的外部调用失败，由全局业务异常处理器负责转换响应。普通业务拒绝保留简短原因；需要定位的系统故障，在合适的异常处理处将异常对象作为最后一个参数传入，保留堆栈，例如 `log.error("数据库操作失败，userId={}", userId, exception)`。未预期异常的全局兜底目前仍待实现。

### 选择可记录的数据

日志只提取必要字段，密码、密码哈希、JWT、Cookie、完整预签名 URL 和私人正文统一排除。Request 和 Entity 按字段选择信息，注册请求整体输出会包含密码。

HTTP 客户端异常消息也可能带有完整签名地址。现有测试上传失败日志仅记录 `statusCode`、实际 `byteSize` 和 `exceptionType`，原始异常继续通过 `BusinessException` 的 `cause` 保留。后续补充堆栈记录时，先确认异常消息及原因链中的凭据已脱敏。

## 8. 常量、配置和交付检查

固定业务限制使用语义明确的 `static final` 常量，例如上传大小上限、地址有效期；同一模块多处使用时集中维护。数据库地址、对象存储地址及账号等环境信息放在 `application.yaml`，本地开发直接使用清楚的本地配置，部署时覆盖对应项。默认值集中在配置或代码中的一处维护。

提交前沿接口流程检查一次：

1. 正常请求能返回明确的 Response 和统一 `Result`。
2. 必填字段缺失、越界数值、非法枚举能返回对应 HTTP 状态及 `code/message/data`。
3. Scalar（`/scalar`）中的字段、示例、枚举取值和成功、失败响应与实际接口一致；必要时查看 `/v3/api-docs`。
4. 涉及数据库或对象存储的流程，验证实际业务效果；当前申请上传地址的成功只代表签名已生成，真实 PUT 上传另行验证。
5. 完成相关编译和测试；数据库、对象存储集成测试使用独立的测试资源，清理本次创建的数据。

基础用法继续查阅：[Spring MVC](help/SpringMVC快速上手.md)、[Scalar](help/Scalar快速上手.md)、[MyBatis-Plus](help/MyBatis-Plus快速上手.md)、[Flyway](help/Flyway快速上手.md)、[RustFS](help/RustFS快速上手.md)。

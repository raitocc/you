# YOU 后端开发约定

新增接口沿着“定义请求 → 编写业务 → 接入 Controller → 验证结果”完成。本文说明项目实际接法；组件的独立示例见文末教程。

## 1. 按业务组织代码

应用根包为 `com.you.youbackend`，当前主要代码如下：

```text
com.you.youbackend
├── common
│   ├── config          # S3、RabbitMQ、OpenAPI、密码散列配置
│   ├── exception       # BusinessException、GlobalExceptionHandler
│   ├── response        # Result
│   └── mybatis         # UUID 与 BINARY(16) 的类型映射
├── user
│   ├── controller      # AuthController、UserController
│   ├── filter          # JwtAuthFilter
│   ├── model           # AppUser、AccessTokenClaims
│   ├── mapper
│   ├── request
│   ├── response
│   └── service         # 用户业务、JWT、令牌黑名单
└── media
    ├── controller
    ├── consumer        # MediaUploadConsumer
    ├── event           # MediaUploadEvent
    ├── entity
    ├── mapper
    ├── request
    ├── response
    └── service
```

新模块沿用实际需要的目录。Service 使用具体的 `@Service` 类；依赖通过构造器注入，字段使用 `final`。普通 Bean 可以通过 `@RequiredArgsConstructor` 注入，包含 `@Value` 时参考 `MediaService` 的显式构造器。

Controller 负责接收、校验、委托和返回；Service 负责业务判断、流程和事务；Mapper 负责数据库读写；Consumer 负责接收消息并委托业务处理。

## 2. 定义请求与响应

入参使用 `动作 + 对象 + Request`，输出使用对应 `Response`。固定字段使用明确类型，例如 `CreateUploadRequest`、`CreateUploadResponse`；动态键值数据可以使用 Map。

请求、响应模型的类与字段添加 `@Schema`，说明含义、单位、示例和特殊值。可空字段写清语义，例如更新资料时 `avatarFileId = null` 表示清除头像。

基础格式校验放在 Request 中：

```java
@Schema(description = "文件字节数，最大 10 MiB", example = "1000000")
@NotNull(message = "请提供文件大小")
@Positive(message = "文件大小必须大于 0")
@Max(value = MAX_UPLOAD_BYTES, message = "文件大小最多为 {value} 字节")
private Long byteSize;
```

必填数字使用包装类型与 `@NotNull`，字符串使用 `@NotBlank`、`@Size`，固定取值使用枚举。JSON 使用 `@Valid @RequestBody`，文件表单使用 `@Valid @ModelAttribute` 并声明 `multipart/form-data`。

普通 JSON 接口由 Controller 返回 `Result<T>`：

```java
@Operation(summary = "申请图片上传地址")
@PostMapping
public Result<CreateUploadResponse> createUpload(
        @Valid @RequestBody CreateUploadRequest request,
        @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
    return Result.ok(mediaService.createUpload(request, userId));
}
```

Controller 类使用 `@Tag`，方法使用 `@Operation`、`@ApiResponses` 描述实际行为。JavaDoc 说明代码职责和方法输入输出，行内注释解释业务原因。接口字段变更时同步响应模型、注解和前端调用。

## 3. 接入当前用户与退出登录

当前使用 JWT Bearer 认证。注册和登录公开；Scalar、OpenAPI、WebJars、错误入口与 OPTIONS 请求由过滤器放行，其余请求进入 `JwtAuthFilter`。

```text
Authorization 请求头
    → JwtService.parseToken：验证签名、签发者、有效期和必要字段
    → TokenBlacklistService：检查 jti 是否撤销
    → 将身份写入 request
    → Controller → Service
```

登录令牌由 HS256 签发，有效期 7 天。`sub` 是用户 ID，`jti` 是每枚令牌的唯一编号，`exp` 是过期时间；`AccessTokenClaims` 保存验证后的结果。

业务接口通过请求属性取得身份：

```java
@GetMapping("/me")
public Result<UserResponse> getCurrentUser(
        @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
    return Result.ok(userService.getCurrentUser(userId));
}
```

需要令牌编号和过期时间的接口使用 `JwtAuthFilter.CURRENT_ACCESS_TOKEN`，类型为 `AccessTokenClaims`。这两个属性来自同一次解析；已有接口继续使用 UUID，退出接口使用完整令牌信息。对象是否属于本人、是否允许操作等业务权限在 Service 中判断。

受保护接口在类或方法上添加 `@SecurityRequirement(name = "bearerAuth")`，Scalar 据此附带认证头。公开与受保护方法并存的 Controller 在受保护方法上标注。

`POST /auth/logout` 将当前 `jti` 写入 Redis：

| 项目 | 当前约定 |
| --- | --- |
| Key | `you:auth:revoked:{jti}`，前缀由 `TokenBlacklistService.KEY_PREFIX` 维护 |
| Value | `1`，通过 Key 是否存在判断撤销状态 |
| TTL | 当前令牌剩余有效时间 |
| 成功响应 | HTTP 200，`Result.ok(null)` |
| 退出效果 | 后续携带同一令牌的请求返回 401，其他令牌继续有效 |

客户端退出后清除本地令牌。每个受保护请求都会查询 Redis，开发时一起启动该服务。令牌新增 `jti` 后，旧令牌通过重新登录替换。

过滤器中的 `BusinessException` 交给 MVC 的 `HandlerExceptionResolver` 转换为统一响应，同时设置 `WWW-Authenticate: Bearer`。这样认证失败与业务接口使用相同的错误结构。

## 4. 使用数据库与事务

Entity 对应数据库记录，Response 表达接口输出。Mapper 使用 `@Mapper` 并继承相应的 `BaseMapper`，查询列和条件优先使用 Lambda 表达式。

| 内容 | 当前接法 |
| --- | --- |
| 主键 | 应用生成 UUIDv7，Java 字段使用 UUID |
| 二进制映射 | 全局 `UuidTypeHandler` 在 UUID 与 `BINARY(16)` 间转换 |
| 审计时间 | Service 按 UTC 填写 `created_at`、`updated_at`，毫秒精度 |
| 软删除 | Java 字段 `deletedAt`，数据库 `deleted_at`；空值有效，删除时填写 UTC 时间 |
| 对象关联 | 应用维护关系与业务约束 |
| 结构变更 | 新增 Flyway 迁移，已执行文件保持原样 |

逻辑删除已在 `application.yaml` 配置，MyBatis-Plus 标准 CRUD 自动处理；自定义 SQL 中显式维护对应条件。更新可空字段时，参考更新头像的 `lambdaUpdate().set(...)` 写法，使清空操作能写入数据库。

多次数据修改需要共同提交时，在 Service 的公开业务方法上使用 `@Transactional`。Consumer 调用受 Spring 管理的 Service，使事务完成后再返回消费结果。

## 5. 理解图片直传和事件入库

当前媒体接口为：

| 接口 | 用途 |
| --- | --- |
| `POST /media/uploads` | 生成 `fileId` 和有效期 10 分钟的 PUT 地址 |
| `POST /media/uploads/test` | 接收 `url`、`file`，帮助执行 PUT，方便联调 |
| `GET /media/uploads/{fileId}` | 查询当前用户的文件是否已经登记就绪 |

申请签名时生成 UUIDv7；它既是返回的 `fileId`，也是后续 `media_file.id`。对象键格式为 `images/{userId}/{fileId}`，用户 ID 来自已验证的请求身份。

```text
客户端申请地址 → 客户端 PUT 到 RustFS → 对象保存成功
                                        ↓
                         s3:ObjectCreated:Put 事件
                                        ↓
                     Exchange → Queue → MediaUploadConsumer
                                        ↓
                        MediaService.handleUploadEvent
                                        ↓
                      解析对象键 → HEAD 查询 → 检查 → 入库
```

签名绑定 `Content-Type`；PUT 使用原始二进制，类型与申请值一致。`byteSize` 是申请时声明值，当前按上传后 HEAD 得到的实际大小检查 10 MiB 上限，允许的 MIME 元数据为 PNG、JPEG、WebP。图片实际内容的解码识别属于独立校验。

`registerFile` 解析对象键取得上传者和文件 ID，并处理重复登记。事件可能重复投递，已存在记录会跳过插入；唯一约束处理并发重复登记。当前文件生命周期沿用已登记文件 ID，上传地址生成成功时尚未建立媒体记录。

`ready = true` 表示存在有效且属于当前用户的文件记录。尚未登记、已删除或属于其他用户的文件均返回 `false`。客户端在就绪后提交资料更新；头像关联时由 `validateOwnedImage` 再次检查归属和类型。

读取资料时返回 `avatarUrl`，由媒体服务生成 10 分钟的 GET 签名。数据库保存文件 ID 和对象键，临时地址在响应时生成。拿到有效地址的客户端可以直接读取对象。

### 配置如何对应

| 位置 | 当前值或职责 |
| --- | --- |
| Compose 的 RustFS 环境变量 | 定义 AMQP 目标 `primary`、交换机和路由键 |
| `S3Config.configureMediaNotifications` | 为 `storage.bucket` 设置 PUT 事件订阅 |
| 目标 ARN | `arn:rustfs:sqs:us-east-1:primary:amqp` |
| `RabbitMqConfig` | 声明交换机 `you.storage.events`、队列 `you.media.uploaded`、路由键 `media.uploaded` |
| `MediaUploadConsumer` | 用 Jackson 3 将消息正文解析为 `MediaUploadEvent` |
| `MediaService` | 检查事件和文件，事务内登记元数据 |

Spring Boot 在宿主机运行，通过 `localhost:5672` 连接 MQ；RustFS 在 Compose 网络中，通过 `rabbitmq:5672` 连接。同一个桶的通知设置通过 S3 API 整体写入，新增订阅时一起维护现有配置。

当前监听容器在方法成功返回后确认消息。业务异常继续向外抛出，默认会重新入队；MQ 消费异常由监听容器处理，HTTP 全局异常处理器负责 Web 请求。处理完成但确认前连接中断也可能引发重复投递，因此重复事件沿用同一文件记录。

### 上传成功后如何排查

1. 用 `docker compose ps` 确认基础服务运行。
2. 检查 RustFS 容器的实际环境变量和桶通知 ARN；改 Compose 后执行 `docker compose up -d rustfs`。
3. 在 RabbitMQ 管理页面看交换机发布、队列投递、确认统计和消费者数。队列为零时，消息可能已经消费完成。
4. 查看后端的上传事件、HEAD 查询和入库日志，再检查 `media_file` 与就绪接口。

RustFS 镜像日志还保存在容器的 `/logs/rustfs.log`；容器标准输出信息不足时，从这里查看通知目标和事件发送错误。

## 6. 统一响应与异常

普通成功响应使用 `Result.ok(data)`，失败使用相同结构：

```json
{
  "code": "INVALID_ARGUMENT",
  "message": "请提供文件大小",
  "data": null
}
```

| 情况 | HTTP 状态 | code |
| --- | --- | --- |
| 普通成功 | 200 | `SUCCESS` |
| 字段校验失败 | 400 | `INVALID_ARGUMENT` |
| JSON、类型或枚举解析失败 | 400 | `INVALID_REQUEST_BODY` |
| 业务失败 | `BusinessException` 携带的状态 | 对应业务错误码 |
| 认证失败 | 401 | `UNAUTHORIZED`，账号密码错误使用 `INVALID_CREDENTIALS` |

Service 对可预期失败抛出 `BusinessException(status, code, message)`。例如当前头像不可用时返回 400、`FILE_NOT_USABLE`；外部上传失败使用带 cause 的构造器保留原始异常。

`GlobalExceptionHandler` 将业务异常转换为 `ResponseEntity<Result<Void>>`，Controller 保持正常调用流程。`Result.fail` 创建响应体，HTTP 状态由处理器或 `ResponseEntity` 设置。二进制与重定向响应按对应 HTTP 用途返回。

当前处理器覆盖字段校验、请求体解析和显式业务异常。multipart 超限、Redis 连接失败等其他框架或系统异常仍使用默认错误处理；接口实际增加对应处理时同步维护这里的覆盖说明。

## 7. 写日志、常量和配置

使用 `@Log4j2`，沿用默认格式和 INFO 级别，消息采用“事件 + 关键字段”并使用 `{}` 占位符。

| 级别 | 使用位置 |
| --- | --- |
| INFO | 注册、登录、退出、资料更新、上传签名和文件登记等关键步骤 |
| WARN | 凭据无效、权限拒绝、文件检查失败等需要关注的情况 |
| ERROR | 外部服务或数据库故障，附带足够的定位信息 |
| DEBUG | 高频查询、重复事件和临时排查信息 |

Service 记录业务事件；Consumer 需要时记录消息处理边界。同一故障选定一个位置记录，事务内日志说明写入步骤，事务提交结果由事务边界决定。

日志选取 `userId`、`fileId`、`jti`、状态码和异常类型等必要字段。密码、密码散列、完整 JWT、Cookie、完整预签名地址和私人正文留在日志之外；HTTP 客户端异常链也应检查是否带有签名地址。

固定业务限制、Key 前缀和路由名称使用语义明确的常量，放在所属模块维护。连接地址和凭据放在配置文件，部署时覆盖；令牌黑名单 TTL 从令牌剩余时间计算。

## 8. 交付前检查

1. 正常流程能返回明确的 Response，必要的失败返回对应状态与统一结构。
2. 受保护接口在无令牌、过期或已撤销时返回 401，业务权限检查使用已验证身份。
3. Scalar 中的字段、可空语义、示例和响应与实际行为一致。
4. 涉及外部组件时验证实际业务结果，区分签名生成、文件上传、事件入库和业务关联。
5. 编译、测试与联调结果分别记录；测试数据使用独立资源并在完成后清理。

组件用法：[Spring MVC](help/SpringMVC快速上手.md)、[MyBatis-Plus](help/MyBatis-Plus快速上手.md)、[Scalar](help/Scalar快速上手.md)、[Flyway](help/Flyway快速上手.md)、[RustFS](help/RustFS快速上手.md)、[JWT](help/JWT认证与退出登录.md)、[RabbitMQ](help/RabbitMQ快速上手.md)。

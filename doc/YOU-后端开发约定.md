# YOU 后端开发约定

## 模块组织

应用根包为 `com.you.youbackend`，采用**按业务模块组织、模块内分层**的架构。

- `common/`：存放跨业务模块复用的基础能力，包括配置、异常处理、统一响应、MyBatis 类型映射等。
- 新业务在根包下创建独立模块，比如当前的 `user` 包与 `media` 包，不按技术层级建立全局 `controller/`、`service/`、`mapper/` 等目录。
- 模块内部按实际需求创建子包，不要求所有模块具有完全相同的目录结构。
- 优先复用现有公共能力，避免重复实现。

新业务模块参考下面基础模块分层结构：

| 目录          | 职责                                                       |
| ------------- | ---------------------------------------------------------- |
| `controller/` | 定义 HTTP 接口，负责参数接收、校验、调用 Service、返回响应 |
| `service/`    | 实现业务逻辑、业务校验、流程编排和事务管理                 |
| `mapper/`     | 使用 MyBatis Plus 访问数据库，封装 SQL 操作及数据映射      |
| `entity/`     | 定义数据库持久化实体，负责与数据库表结构映射               |
| `model/`      | 定义业务数据模型，封装业务领域中的数据结构                 |
| `request/`    | 定义 API 请求 DTO，不直接使用持久化对象接收请求            |
| `response/`   | 定义 API 响应 DTO，不直接暴露持久化对象                    |

Service 使用具体的 `@Service` 类；依赖通过构造器注入，字段使用 `final`。普通 Bean 可以通过 `@RequiredArgsConstructor` 注入，包含 `@Value` 时参考 `MediaService` 的显式构造器。

Controller 负责接收、校验、委托和返回；Service 负责业务判断、流程和事务；Mapper 负责数据库读写；Consumer 负责接收消息并委托业务处理。

## 技术框架

当前后端技术框架选型，

| 用途       | 技术选型                    |
| ---------- | --------------------------- |
| 基础框架   | Spring Boot **4.1.2**       |
| HTTP API   | Spring MVC、Jakarta Servlet |
| JSON       | Jackson 3                   |
| 数据访问   | **MyBatis-Plus**            |
| 数据库迁移 | Flyway                      |
| 对象存储   | **RustFS**                  |
| 日志       | **Apache Log4j 2**          |
| API 文档   | Scalar                      |
| ID 生成    | UUIDv7                      |
| 编译期辅助 | Lombok                      |

- **技术栈优先**：优先使用项目现有框架、依赖及其官方提供的能力解决问题，不重复实现已有的成熟功能，不随意引入功能重叠的新依赖。
- **遵循框架惯例**：遵循各框架和组件的官方推荐用法、惯用 API、注解、配置机制及设计模式，充分利用框架提供的声明式能力，避免不必要的底层实现和样板代码。
- **保持技术一致性**：新增功能应与现有项目的技术选型、代码风格、架构分层及实现方式保持一致，不擅自替换技术组件或引入平行实现。
- **优先复用现有实现**：开发前检查项目中已有的公共组件、工具类、配置和业务实现，优先复用或合理扩展，避免重复封装。
- **控制抽象复杂度**：仅在现有技术栈无法合理满足业务需求时引入自定义实现或额外依赖，避免过度封装、冗余抽象及不必要的复杂性。
- **版本兼容性**：所有 API、配置及实现方式必须与项目实际使用的依赖版本兼容，不使用已废弃或不受支持的功能。

## 代码质量

- **可读性优先**：使用语义明确的命名和清晰的代码结构，使代码易于理解，避免晦涩写法、过深嵌套及复杂控制流。
- **简洁精炼**：以满足业务需求的最简单实现为优先，减少重复代码、冗余判断、无意义的中间变量及不必要的抽象，不为追求简短而牺牲可读性。
- **职责清晰**：类、方法及模块应具有明确的职责，合理拆分复杂逻辑，避免单个方法承担过多功能或产生过度碎片化的封装。
- **适度注释**：对业务方法提供必要的中文注释，重点解释“负责什么业务”，避免重复描述代码行为。比如controller、service、mapper方法都需要有简洁的中文注释，示例：
  */***&#xA; ** 登录并取得访问令牌和本人资料。*&#xA; **/*

- **避免防御性编码**：不对已由框架、类型系统或上游逻辑保证的条件进行重复校验，不添加缺乏明确需求依据的兼容分支、异常捕获或兜底逻辑；必要的输入校验、安全边界和错误处理必须保留。
- **风格一致**：遵循项目既有的命名规范、格式化规则与代码组织方式，避免同一项目中出现多套不同的实现风格。







## 定义请求与响应

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

## 接入当前用户与退出登录

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

## 使用数据库与事务

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

## 理解图片直传和事件入库

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

## 统一响应与异常

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

## 写日志、常量和配置

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

## 交付前检查

1. 正常流程能返回明确的 Response，必要的失败返回对应状态与统一结构。
2. 受保护接口在无令牌、过期或已撤销时返回 401，业务权限检查使用已验证身份。
3. Scalar 中的字段、可空语义、示例和响应与实际行为一致。
4. 涉及外部组件时验证实际业务结果，区分签名生成、文件上传、事件入库和业务关联。
5. 编译、测试与联调结果分别记录；测试数据使用独立资源并在完成后清理。

组件用法：[Spring MVC](help/SpringMVC快速上手.md)、[MyBatis-Plus](help/MyBatis-Plus快速上手.md)、[Scalar](help/Scalar快速上手.md)、[Flyway](help/Flyway快速上手.md)、[RustFS](help/RustFS快速上手.md)、[JWT](help/JWT认证与退出登录.md)、[RabbitMQ](help/RabbitMQ快速上手.md)。

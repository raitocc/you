# YOU

个人记忆平台，以瞬间记录经历，通过时光、同行和圈子整理与分享。后端位于 `you-backend/`，使用 Java 17、Spring Boot 4.1.2。

## 当前进度

已实现注册、登录、退出、本人资料读取与更新，以及图片直传地址申请、上传就绪查询和头像临时访问地址。图片上传事件通过 RabbitMQ 消费，检查后写入媒体文件表；退出登录通过 Redis 撤销当前 JWT。

基础工程包括 MyBatis-Plus、MySQL、Flyway、Scalar、Log4j2、AWS S3 SDK、JJWT、RabbitMQ 和 Redis。初始迁移包含 14 张业务表；UUIDv7 使用 `BINARY(16)`，删除时间使用 `deleted_at`，关联关系由应用维护。瞬间、时光、同行和圈子的业务接口继续在这些基础上开发。

## 启动项目

准备 JDK 17 或 21、Maven 3、Docker Desktop。IDEA 导入 `you-backend/pom.xml`，语言级别保持 17。

在仓库根目录执行：

```powershell
docker compose up -d
```

打开 RustFS 控制台，使用 `you` / `you` 登录，首次创建私有桶 `you-files`。后端启动时会为这个已有桶设置上传事件订阅。

在 IDEA 运行 `YouBackendApplication`，或在根目录执行：

```powershell
mvn -f you-backend/pom.xml spring-boot:run
```

应用启动时，Flyway 自动执行待执行迁移；Spring 声明 RabbitMQ 交换机、队列和绑定；`S3Config` 设置桶通知。连接配置集中在 [application.yaml](you-backend/src/main/resources/application.yaml)。

| 服务 | 本机地址 | 开发凭据 |
| --- | --- | --- |
| 后端 / Scalar | `http://localhost:8080/scalar` | 业务接口使用登录返回的 Bearer Token |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` | 公开访问 |
| MySQL | `localhost:3306`，数据库 `you` | 用户、密码均为 `you` |
| RustFS S3 / 控制台 | `http://localhost:9000` / `http://localhost:9001` | Access Key、Secret Key 均为 `you` |
| RabbitMQ / 管理页面 | `localhost:5672` / `http://localhost:15672` | 用户、密码均为 `you` |
| Redis | `localhost:6379` | 密码 `you` |

基础服务由 [compose.yaml](compose.yaml) 编排，端口绑定本机，数据保存在命名卷中。修改 Compose 环境变量后执行 `docker compose up -d 服务名`，让 Compose 重新创建对应容器并加载配置。

## 跑通一次登录与图片上传

在 Scalar 中按下列顺序操作：

1. `POST /auth/register`：提交用户名、密码和昵称。
2. `POST /auth/login`：取得 `data.accessToken`，在 Scalar 认证区域填入令牌。
3. `POST /media/uploads`：按实际图片填写 `byteSize` 和 `mimeType`，保存返回的 `fileId` 与 `url`。
4. 向 `url` 发送 PUT，正文为图片二进制，`Content-Type` 与申请值一致。也可以调用 `POST /media/uploads/test`，用表单提交 `url` 和 `file`，由后端帮助执行这次 PUT。
5. `GET /media/uploads/{fileId}`：查询 `ready`；事件处理完成并登记文件后为 `true`。
6. `PUT /users/me`：提交完整可编辑资料，例如 `{"nickname":"小满","avatarFileId":"已就绪的文件ID"}`。传 `null` 可清除头像。
7. `GET /users/me`：查看资料与 `avatarUrl`，头像地址有效期为 10 分钟。
8. `POST /auth/logout`：撤销当前令牌。再次使用它访问本人资料会返回 401。

上传链路为：

```text
申请地址 → 客户端 PUT → RustFS → RabbitMQ → 媒体服务检查并入库
                                                    ↓
                                              查询 ready 并关联使用
```

申请时的 `fileId` 就是后续 `media_file.id`。签名有效期为 10 分钟，当前支持 PNG、JPEG、WebP，上传后按实际字节数检查 10 MiB 上限。MQ 消费是异步过程，上传 PUT 成功和文件业务就绪是两个阶段。

## 文档

| 文档 | 内容 |
| --- | --- |
| [后端开发约定](doc/YOU-后端开发约定.md) | 分层、接口表达、认证接入、上传链路、异常和日志 |
| [数据库设计](doc/YOU-数据库设计.md) | 14 张表的字段、关系与业务规则 |
| [Spring MVC](doc/help/SpringMVC快速上手.md) | 接收参数、编写接口和返回结果 |
| [MyBatis-Plus](doc/help/MyBatis-Plus快速上手.md) | 实体、Mapper、增删改查与条件查询 |
| [Scalar](doc/help/Scalar快速上手.md) | 接口说明注解与在线调试 |
| [Flyway](doc/help/Flyway快速上手.md) | 建表、数据库升级与迁移历史 |
| [RustFS](doc/help/RustFS快速上手.md) | SDK、GET/PUT 签名、元数据和上传事件通知 |
| [JWT 认证与退出登录](doc/help/JWT认证与退出登录.md) | 签发验证、请求过滤与 Redis 黑名单 |
| [RabbitMQ](doc/help/RabbitMQ快速上手.md) | 声明资源、发送消费、JSON 和消息确认 |
| [数据库连接与实操](doc/help/数据库连接与实操.md) | 用独立的 test 表练习连接和 CRUD |
| [文档写作规范](doc/文档写作规范.md) | 文档结构、讲解、示例与表达规范 |

通用教程使用独立示例资源；项目接法以开发约定和当前 Scalar 为准。仓库中的接口设计稿保留了早期方案，本次联调使用上面的实际路径。

## 继续开发与验证

业务代码在 `src/main/java/com/you/youbackend` 下按模块组织，测试在 `src/test/java`。新增接口复用 `Result`、`BusinessException`、过滤器中的用户身份和全局 UUID 类型处理器；时间字段由 Service 按 UTC 填写，软删除映射已配置。

改表时在 `db/migration` 新增 `V1.1__描述.sql`、`V1.2__描述.sql` 等迁移，已执行文件保留原样。接口字段、Scalar 注解和前端调用一起维护。

```powershell
# 编译主代码和测试代码
mvn -f you-backend/pom.xml test-compile -DskipTests

# 打包，跳过测试执行
mvn -f you-backend/pom.xml clean package -DskipTests
```

现有练习测试包含已删除的 `test` 表和独立的 `demo-files` 桶，运行前按对应教程准备资源。MQ 上传到数据库的完整链路已验证；其余功能按具体测试和接口联调结果确认。

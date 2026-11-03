# YOU

个人记忆平台，后端位于 `you-backend/`，Java 17、Spring Boot 4、MyBatis、MySQL 8.4、RustFS。

已实现用户注册、登录、退出、读取及替换本人资料、受保护头像读取，以及 media 预签名上传和确认。业务代码位于 `com.you`，启动类为 `YouApplication`。

## 接口契约

- [用户接口](doc/用户模块接口.md)
- [媒体接口](doc/媒体接口.md)
- [数据库结构](you-backend/src/main/resources/db/migration/V1.0__init_schema.sql)

旧版 `YOU-接口设计文档.md` 和 `YOU-openapi.json` 中的用户头像上传、读取方案已经过时；本次用户及媒体实现以上面两份契约为准。

## 启动

```powershell
docker compose up -d
$env:JWT_SECRET = "替换为至少32字节的随机密钥并妥善保存"
mvn -f you-backend/pom.xml spring-boot:run
```

先在 RustFS 控制台创建私有桶 `you-files`。应用不会修改现有桶策略或自动开放桶。S3 接口默认 `http://localhost:9000`，控制台 `http://localhost:9001`。本机开发账号见 `compose.yaml`。Flyway 自动执行数据库迁移。

配置文件为 [application.yaml](you-backend/src/main/resources/application.yaml)，可用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`STORAGE_ENDPOINT`、`STORAGE_PUBLIC_ENDPOINT`、`STORAGE_ACCESS_KEY`、`STORAGE_SECRET_KEY`、`STORAGE_BUCKET`、`JWT_SECRET` 覆盖配置。`STORAGE_PUBLIC_ENDPOINT` 必须可由客户端直接访问，用于生成签名地址；不要在生成签名后改写地址。

Scalar 位于 `/scalar`，OpenAPI JSON 位于 `/v3/api-docs`。

## 已落实的规则

用户名仅允许 ASCII 字母开头的 4～64 位字母、数字、下划线，统一小写并通过数据库唯一索引保证不可复用。密码使用随机盐 PBKDF2-HMAC-SHA256 自适应散列，保留空白和完整字符。JWT 固定 HS256，校验签名、有效期、签发者和受众，有效期七天，退出不撤销令牌。

登录按规范化账号每分钟 10 次、来源 IP 每分钟 30 次限制，可在 `auth.login` 调整。IP 使用实际连接地址，部署代理时应在可信代理层处理客户端来源，不能直接信任任意请求的转发头。

头像访问资格包括本人、有效同行共同成员（包括已结束但未解散的同行）、有效圈子共同成员、当前可访问瞬间的作者。瞬间资格根据有效同行或圈子直接分享判断，时光引用不额外授权。无资格统一返回 404；成功返回有效期五分钟的 GET 签名和 `Cache-Control: private, no-store`。

上传流程为申请十分钟 PUT 凭据、向 RustFS 上传临时对象、确认上传。确认时先复制到独立最终键，再检查该快照的实际类型、声明大小、完整解码与四千万像素上限，校验成功才写入 `media_file`。支持 JPEG、PNG、WebP，最大 10 MiB。并发或重复确认返回同一 fileId，旧 PUT 地址无法改变已确认内容。替换头像只改变引用，不删除文件。

上传凭据持久化在 `media_upload` 表，Flyway 的 `V1.1__media_upload.sql` 自动新增。确认通过数据库事务和行锁串行化，文件记录与确认结果原子提交，支持重启及多实例重复确认。成功确认结果自确认时起保留 24 小时，超过十分钟的未确认凭据返回 410；过期凭据在额外保留 24 小时后清理。定时清理只回收凭据和临时对象，不删除已确认文件。数据库结果未知时保留最终对象，避免误删可能已提交的文件；因进程异常遗留的无引用对象仍需后续独立的引用回收任务处理。

## 验证

```powershell
# 无外部服务的单元测试和打包
mvn -f you-backend/pom.xml clean package
# 本机 MySQL + RustFS 的真实 HTTP 集成测试
mvn -f you-backend/pom.xml '-Dtest=ContractTest,UploadServiceTest,UserMediaIT' test
# 新 JVM 验证：先打包，再启动独立进程读取已确认结果
mvn -f you-backend/pom.xml '-Dtest=ContractTest,UploadServiceTest,UserMediaIT' '-Drestart.jar=target/you-backend-0.0.1-SNAPSHOT.jar' test
```

集成测试使用独立 `you-contract-tests` 桶，结束后删除测试用户和文件；不使用正式桶。测试覆盖注册唯一性、JWT、资料严格校验、头像归属、上传确认、临时对象覆盖隔离、三类头像权限及撤销、退出后令牌仍有效。旧 `/test` 教学示例仅位于测试源码，数据库与 RustFS 示例需要显式设置 `RUN_LEGACY_EXAMPLES=true`，并准备教程中的 `test` 表及 `demo-files` 桶；旧上下文测试通过 `RUN_INFRA_TESTS=true` 启用并需设置 JWT 密钥。

## 相关资料

[数据库设计](doc/YOU-数据库设计.md)、[技术设计](doc/YOU-TRD.md)、[RustFS 教程](doc/help/RustFS快速上手.md)、[MyBatis 教程](doc/help/MyBatis-Plus快速上手.md)。

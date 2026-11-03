# YOU

个人记忆平台，以瞬间记录经历，通过时光、同行和圈子整理与分享。后端位于 `you-backend/`，当前已完成基础工程，接下来开始业务开发。

## 已完成

- **工程**：IDEA 创建的 Spring Boot 4.1.2 项目，Java 17，启动包 `com.you.youbackend`。整个 YOU 使用一个 Git 仓库，已配置 `.gitignore`。
- **依赖**：Spring MVC、MyBatis-Plus 3.5.18、MySQL 驱动、Flyway、springdoc + Scalar 3.2.0、Log4j2、Lombok、AWS S3 SDK 2.57.8，以及 Spring Boot MVC 测试依赖。
- **数据库**：已设计 14 张业务表，写入 [V1.0__init_schema.sql](you-backend/src/main/resources/db/migration/V1.0__init_schema.sql)。UUIDv7 使用 `BINARY(16)`，通过 `deleted_at` 软删除，关联关系由应用维护。迁移目前仅完成文件核对，执行验证待完成。
- **对象存储**：已配置 `S3Config`，可注入 `S3Client` 访问 RustFS。
- **测试**：Controller、数据库连接与 CRUD、RustFS 上传读取示例均在 `src/test/java`。已通过清理后的编译打包，正式 JAR 中已排除测试类；完整测试运行验证待完成。
- **容器**：[compose.yaml](compose.yaml) 编排 MySQL 8.4 和 RustFS，已配置本机端口映射和持久化数据卷。

## 文档与快速教程

| 文档 | 内容 |
|---|---|
| [数据库设计](doc/数据库设计.md) | 14 张表的字段、关系与业务规则 |
| [Spring MVC](doc/SpringMVC快速上手.md) | 编写接口、接收参数和返回结果 |
| [MyBatis-Plus](doc/MyBatis-Plus快速上手.md) | 实体、Mapper、增删改查与条件查询 |
| [Scalar](doc/Scalar快速上手.md) | 接口说明注解与在线调试 |
| [Flyway](doc/Flyway快速上手.md) | 建表、数据库升级与迁移版本管理 |
| [RustFS](doc/RustFS快速上手.md) | 部署、上传、下载与临时访问地址 |
| [数据库连接与实操](doc/数据库连接与实操.md) | 用 test 表练习数据库连接和 CRUD |

## 启动使用

准备 JDK 17 或 21、Maven 3、Docker Desktop。IDEA 导入 `you-backend/pom.xml`，语言级别保持 17。

在 YOU 根目录执行：

```powershell
docker compose up -d
mvn -f you-backend/pom.xml -DskipTests clean package
```

应用启动时，Flyway 自动执行待执行的迁移并记录历史。MySQL 数据库、账号和密码均为 `you`，端口为 `3306`。RustFS Access Key 和 Secret Key 均为 `you`，S3 接口为 `http://localhost:9000`，控制台为 `http://localhost:9001`。

接口文档：<http://localhost:8080/scalar>；OpenAPI JSON：<http://localhost:8080/v3/api-docs>。连接配置在 `you-backend/src/main/resources/application.yaml`。

现有练习中的 CRUD 使用已删除的 `test` 表，文件测试使用 `demo-files` 桶；运行相应练习时按教程准备表或桶。上面的打包命令只编译测试源码，跳过测试执行。

## 继续开发

业务代码放在 `src/main/java/com/you/youbackend` 下，按业务建立包；测试放在 `src/test/java`。先落实 UUIDv7 生成及二进制转换、审计时间填充和逻辑删除映射，再跑通“注册登录 → 上传图片 → 发布瞬间 → 浏览、编辑、删除”的基础流程。

后续改表在 `src/main/resources/db/migration` 新增 `V1.1__描述.sql`、`V1.2__描述.sql` 等迁移；已执行的历史文件保持原样。多人同时改表时协调版本号，接口说明随 Controller 一起维护到 Scalar。

```
com.you
├── YouApplication.java
├── config                      # MVC、认证、MyBatis、存储、AI 等配置
├── common
│   ├── exception               # 异常及统一异常处理
│   ├── response                # 统一响应、公共错误码、分页结果等
│   ├── mybatis                 # UUID 等公共类型转换
│   └── util                    # 实际需要的通用工具
├── user                        # 注册、登录、退出、当前用户、个人资料
├── moment                      # 瞬间、组图、标签、浏览、搜索、关联调整
├── album                       # 时光及其中的瞬间
├── gathering                   # 一次共同经历、成员、邀请码与阶段管理
├── circle                      # 圈子、成员、内容分享
├── media                       # 图片上传、文件元数据、访问地址、RustFS 接入
├── message                     # 用户信箱、站内通知及各自的已读管理
└── ai                          # 辅助填写、语义检索、整理与生成能力
```

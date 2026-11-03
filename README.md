# YOU

YOU 是个人记忆平台，以瞬间记录经历，通过时光、同行和圈子整理与分享。

当前仓库包含 Spring Boot 后端基础工程、MySQL 与 RustFS 本机编排，以及数据库设计和开发教程。正式业务功能从这份基础上开始实现。

第一次接手请阅读 **[开发交接](doc/开发交接.md)**：从空项目到现在的配置、环境启动、迁移状态、代码位置和协作起点都在其中。

| 目录或文件 | 用途 |
|---|---|
| `you-backend/` | Java 17 / Spring Boot 4.1.2 后端 |
| `compose.yaml` | MySQL 8.4、RustFS 本机开发环境 |
| `doc/` | 数据库设计、技术教程与交接文档 |

表结构参见 [数据库设计](doc/数据库设计.md)，初始迁移为 `you-backend/src/main/resources/db/migration/V1.0__init_schema.sql`。首次启动应用会由 Flyway 执行迁移，请按交接文档中的接入顺序操作。

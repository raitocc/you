# Flyway：从首次建表到数据库升级

跟随本教程，为一张用户表建立迁移历史：第一次启动创建表，第二次启动增加邮箱字段，并通过历史表确认执行结果。

示例使用 Java 17、Spring Boot 4、MySQL。准备一个可启动的 Spring Boot 项目、空数据库 `flyway_demo`，以及具备读写、建表和改表权限的账号。示例连接账号和密码均为 `demo`。

新库接入，顺序阅读第 1～3 节；日常维护查第 4 节；接管已有数据库查第 5 节；扩展配置和错误处理查第 6～7 节。

## 1. 理解迁移文件与执行历史

Flyway 把数据库变更保存为带版本号的 SQL 文件，按版本执行，并将执行结果写入历史表。

```text
迁移文件 → Flyway 检查历史 → 执行待执行的 SQL → 记录结果
```

本文统一使用三个名称：

| 名称 | 含义 |
|---|---|
| 迁移文件 | 一次数据库变更的 SQL 文件，例如 `V1__create_demo_user.sql` |
| 迁移版本 | 文件名中的版本号，例如 1、2、3 |
| 历史表 | 数据库中的 `flyway_schema_history`，记录执行过的迁移 |

SQL 定义具体变更，Flyway 管理执行顺序和历史，MyBatis-Plus 或 JPA 负责应用的数据读写。

## 2. 接入 Flyway，完成首次建表

### 2.1 添加依赖

在 `pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>

<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>

<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

Spring Boot 管理这些依赖的版本。starter 提供启动集成，`flyway-mysql` 提供 MySQL 支持，JDBC 驱动负责连接数据库。[Spring Boot 接入说明](https://docs.spring.io/spring-boot/how-to/data-initialization.html)

### 2.2 配置数据库连接

编辑 `src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/flyway_demo
    username: demo
    password: demo
```

Flyway 默认使用应用的数据源，并在 Spring Boot 启动时执行迁移。数据库地址和账号对应前面准备的空库。

### 2.3 编写第一个迁移文件

在 `src/main/resources` 下创建目录和文件：

```text
src/main/resources/
├── application.yaml
└── db/
    └── migration/
        └── V1__create_demo_user.sql
```

写入 `V1__create_demo_user.sql`：

```sql
CREATE TABLE demo_user (
    id   BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL
);
```

文件名由四部分组成：

```text
V1__create_demo_user.sql
│││                 └─ SQL 文件扩展名
││└─ 两个下划线，后面是变更描述
│└─ 迁移版本 1
└─ V 表示版本化迁移
```

SQL 文件使用 UTF-8 保存。默认扫描目录为 `db/migration`。

### 2.4 启动并确认结果

重新加载 Maven，运行 Spring Boot 启动类。Flyway 会创建历史表，再执行版本 1。

在数据库工具中执行：

```sql
SHOW TABLES;

SELECT version, description, script, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

预期看到 `demo_user` 和 `flyway_schema_history` 两张表，历史记录包含：

| version | description | script | success |
|---|---|---|---|
| 1 | create demo user | V1__create_demo_user.sql | 1 |

再次启动应用，Flyway 会根据历史记录确认版本 1 已完成，数据库保持当前结构。

## 3. 新增一次变更，为表增加邮箱字段

### 3.1 创建版本 2

保留版本 1 的内容，在同一目录新增 `V2__add_user_email.sql`：

```sql
ALTER TABLE demo_user
ADD COLUMN email VARCHAR(128) NULL;
```

目录现在是：

```text
db/migration/
├── V1__create_demo_user.sql
└── V2__add_user_email.sql
```

### 3.2 重启并检查字段

重启应用，Flyway 将执行版本 2。在数据库中查询：

```sql
SHOW COLUMNS FROM demo_user;

SELECT version, script, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

预期字段为 `id`、`name`、`email`，历史表中版本 1、2 的 `success` 均为 1。

这就是后续升级的基本流程：**新增迁移文件 → 启动执行 → 检查结构与历史 → 提交 Git**。

一套全新的数据库读取这两个文件时，会依次执行版本 1、2，得到相同的最终结构。[版本化迁移说明](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)

## 4. 在日常开发中维护迁移

### 4.1 每次变更新增一个版本

成功执行并提交的迁移文件保留原样。后续改表、建索引或修正数据，通过下一个版本表达。

例如新增邮箱索引，创建 `V3__add_user_email_index.sql`：

```sql
CREATE INDEX idx_demo_user_email ON demo_user (email);
```

文件名描述这次变更，SQL 给出执行内容。一个文件可以包含多条相关语句，围绕同一项变更组织即可。

### 4.2 同时提交迁移与应用代码

增加业务字段时，将迁移 SQL、实体映射和相关接口代码放入同一次提交。其他开发者拉取后启动应用，Flyway 会补齐他们数据库中的待执行版本。

多人同时编写迁移时，在合并前确认版本号唯一，并让新版本接在团队最新版本之后。SQL 的执行顺序按版本号比较，例如 2 位于 10 之前。

### 4.3 用校验和识别历史文件变化

历史表保存文件的校验和（checksum）。启动时的校验会比较本地文件与已执行记录；文件内容发生变化时，会报告对应版本的差异。

已发布变更需要调整时，保留原迁移，再新增一个修正版本。数据库变更过程因此可以从 Git 与历史表一起追溯。[迁移校验说明](https://documentation.red-gate.com/flyway/reference/commands/validate)

## 5. 将已有数据库接入 Flyway

已有表的数据库通过基线（baseline）确定管理起点。基线记录的含义是：“这个数据库已经具备指定版本的结构，后续从更高版本继续执行”。

以下以“已有结构与版本 1 一致”为例。

### 5.1 为现有结构保存版本 1

将现有表结构保存为 `V1__create_demo_user.sql`。核对目标库中的受管理结构与版本 1 一致，包括字段、索引和约束。

空库以后可以执行这份文件重建初始结构；现有库则将这个版本登记为基线。

### 5.2 在接管这次启动中登记基线

在现有 `spring:` 配置下合并：

```yaml
spring:
  flyway:
    baseline-on-migrate: true
    baseline-version: "1"
```

确认数据源指向准备接管的数据库，然后启动应用。Flyway 会在已有结构且尚无历史表的库中登记版本 1 的基线，并执行版本大于 1 的迁移。

例如目录里已有版本 2，启动后会在现有表上增加 `email`。历史表中可看到基线记录以及版本 2 的执行记录。

### 5.3 恢复日常迁移配置

接管成功后，移除本次添加的两个基线配置项。后续继续使用新版本文件升级。

基线表达对现有结构的确认，实际结构的核对在登记前完成。[基线说明](https://documentation.red-gate.com/flyway/reference/commands/baseline)、[启动时登记基线的条件](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting)

## 6. 按使用场景扩展配置

### 6.1 在 Spring Boot 3 中接入

Boot 3 使用 `org.flywaydb:flyway-core` 替代第 2.1 节的 `spring-boot-starter-flyway`；其余 MySQL 模块和驱动按所用 Boot 管理的 Flyway 版本选择。Boot 3.5 + MySQL 的依赖为：

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>

<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>

<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

迁移文件目录、命名方式和数据源配置沿用前文。[Boot 3.5 接入说明](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)

### 6.2 修改迁移目录

需要按数据库类型组织脚本时，可以配置：

```yaml
spring:
  flyway:
    locations: classpath:db/migration/mysql
```

对应文件放在 `src/main/resources/db/migration/mysql/`。应用的建表和升级脚本统一由这套迁移管理；已有 `schema.sql` 或 ORM 自动建表配置的项目，在接入时将结构创建职责统一到 Flyway。

### 6.3 用可重复迁移维护视图

视图等对象适合使用可重复迁移（repeatable migration）。文件名以 `R__` 开头，例如 `R__user_names.sql`：

```sql
CREATE OR REPLACE VIEW user_names AS
SELECT id, name FROM demo_user;
```

Flyway 会在待执行的版本化迁移之后处理这类文件，首次执行，并在文件校验和变化时再次执行。这里用 `CREATE OR REPLACE` 让视图定义可以反复应用。[可重复迁移说明](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/repeatable-migrations)

### 6.4 区分迁移、校验和历史修复

Spring Boot 默认在启动时调用 `migrate`。需要通过 Flyway CLI 或 Maven 插件单独操作时，可按目标查阅：

| 操作 | 用途 |
|---|---|
| `migrate` | 执行待执行迁移 |
| `info` | 查看已执行、待执行等迁移状态 |
| `validate` | 校验迁移文件与执行历史的一致性 |
| `baseline` | 为已有数据库登记管理起点 |
| `repair` | 修复历史表中的失败记录或元数据 |

独立工具使用自己的连接和脚本目录配置。`repair` 处理历史元数据，数据库中实际对象的修复由 SQL 完成。[历史表修复说明](https://documentation.red-gate.com/flyway/reference/commands/repair)

## 7. 定位迁移执行问题

| 现象 | 检查与处理 |
|---|---|
| 应用启动后迁移未执行 | 检查 starter 是否加载、Flyway 是否启用、数据源是否连接目标库 |
| 迁移文件未被发现 | 核对目录、文件名中的双下划线及 `.sql` 扩展名 |
| 版本号重复 | 为不同变更分配唯一版本号，再检查执行顺序 |
| MySQL 支持模块相关错误 | 核对 `flyway-mysql` 和 JDBC 驱动是否在依赖中 |
| 已有表且缺少历史表 | 按第 5 节核对现有结构并登记基线 |
| `Migration checksum mismatch` | 将已执行文件恢复为提交时的内容，再用新版本表达调整 |
| SQL 执行失败 | 根据日志定位版本与失败语句，检查库中已完成的操作后修复 |

MySQL 的许多建表、改表语句会隐式提交，因此一个文件中的前序语句可能已经生效。处理执行失败时，先核对实际表结构，再修复 SQL 和必要的历史记录，然后重新执行迁移。[MySQL 隐式提交说明](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html)

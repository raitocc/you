# YOU-测试文档

## 测试用例

项目的唯一测试用例来源：`you\doc\customer-tests`。

使用 Customer Tests（客户测试）作为测试标准。当前阶段主要面向服务端设计测试，暂不考虑客户端。将每个客户测试设计为 yaml 文件，具体结构如下：

```yaml
# M01 发布瞬间
## 约定
### 1. 数据隔离：每条测试用例独立初始化与清理数据，用例间互不影响。
### 2. 实体标识前缀：
#### - A, B, C：已注册测试用户（未显式声明 authenticated: false 时，默认已登录）。
#### - IMG1~IMG3：图片资源标识（readyImages 为已就绪可发布图片，failedImages 为上传失败的附件）。
#### - G1, G2：同行（Gathering）标识；C1, C2：圈子（Circle）标识（未声明 status 时默认正常可用）。
### 3. 变量引用：as: <var> 保存当前步骤输出；$<var>.<field> 引用其字段值（如 $result.id）。
### 4. 断言视角：browse* 动作断言持久化后的可见性结果。
id: MOMENT-CREATE-001
module: moment

tests:
  - name: shouldCreateTextOnlyMomentWhenContentIsProvided
    comment: 用户已登录时，发布纯文字瞬间，成功
    given: {user: A, authenticated: true}
    steps:
      - {actor: A, action: createMoment, content: "今天很开心", as: result}
    then:
      result: {success: true, author: A, content: "今天很开心", images: []}

  - name: shouldCreateImageOnlyMomentWhenContentIsBlank
    comment: 正文空白、但存在有效图片时，发布图片瞬间，成功
    given: {user: A, authenticated: true, readyImages: [IMG1]}
    steps:
      - {actor: A, action: createMoment, content: "  ", images: [IMG1], as: result}
    then:
      result: {success: true, author: A, content: "", images: [IMG1]}

  - name: shouldRejectCreationWhenUserIsUnauthenticated
    comment: 未登录时，发布瞬间，失败
    given: {user: A, authenticated: false}
    steps:
      - {actor: A, action: createMoment, content: "今天很开心", as: result}
    then:
      result: {success: false, error: UNAUTHORIZED}
```

### **字段规范**

| 字段              | 类型   | 含义                                                         |
| ----------------- | ------ | ------------------------------------------------------------ |
| `id`              | String | 功能唯一标识，例如 `MOMENT-CREATE-001`，一个功能对应一组测试 |
| `module`          | String | 功能所属的业务模块，例如 `moment`、`album`、`circle`         |
| `tests`           | Array  | 该功能对应的客户测试用例集合，每个元素代表一个独立的测试用例 |
| `tests[].name`    | String | 英文测试名称，使用 `shouldXxxWhenYyy` 风格，在所属功能内唯一 |
| `tests[].comment` | String | 中文说明该测试用例的测试目的和业务场景                       |
| `tests[].given`   | Object | 测试执行前的业务状态、前置条件及输入数据                     |
| `tests[].steps`   | Array  | 测试需要执行的一系列业务操作，不涉及内部实现细节             |
| `tests[].then`    | Object | 预期业务结果、系统可观察状态及断言条件                       |

### **字段约束**

- `id`：必须唯一，标识一个业务功能，而非单个测试用例。
- `module`：必填，表示功能所属模块。
- `tests`：必填，至少包含一个测试用例。
- `name`：必填，在所属功能内唯一，建议使用 `shouldXxxWhenYyy` 风格。
- `comment`：必填，使用中文，使用“when-what-that”的句式，例如“用户已登录时，发布纯文字瞬间，成功”、“正文空白、但存在有效图片时，发布图片瞬间，成功”。
- `given`：可以为空，例如测试无须准备数据。
- `steps`：至少包含一个业务操作，只描述业务行为，不指定 Controller、Service、Mapper 等内部调用。
- `then`：至少包含一项可验证的预期结果。error 错误码使用后端规定错误码。

### 约定

约定作为注释写作yaml文件的开头，示例：

```yaml
# M01 发布瞬间
## 约定
### 1. 数据隔离：每条测试用例独立初始化与清理数据，用例间互不影响。
### 2. 实体标识前缀：
#### - A, B, C：已注册测试用户（未显式声明 authenticated: false 时，默认已登录）。
#### - IMG1~IMG3：图片资源标识（readyImages 为已就绪可发布图片，failedImages 为上传失败的附件）。
#### - G1, G2：同行（Gathering）标识；C1, C2：圈子（Circle）标识（未声明 status 时默认正常可用）。
### 3. 变量引用：as: <var> 保存当前步骤输出；$<var>.<field> 引用其字段值（如 $result.id）。
### 4. 断言视角：browse* 动作断言持久化后的可见性结果。
```



## 技术选型

1. JUnit 5 、 @SpringBootTest 、 MockMvc
2. 使用 Testcontainers 自动拉起与仓库 Docker Compose 一致的 **MySQL 8.4** 和 **Redis 7** 容器，保证测试环境与开发环境一致
3. 测试库表结构直接使用开发环境的 Flyway 迁移脚本，保证测试环境 Schema 与开发环境一致



## 测试

项目的唯一测试用例来源：`you\doc\customer-tests`。编写的测试需要基于我们的测试用例，YAML 文件是用表达设计测试场景的结构化文件。写测试时，基于测试用例的结构性表达，YAML 里的字段名不用和代码一一对应，测试用例与实际实现应当解耦，目标是测试用例要求的行为和结果。

1. 测试类命名规则：将 YAML 用例文件名（去掉 `.yaml` 后缀）从 `snake_case` 转为 `PascalCase`，并在末尾拼接 `CustomerTest`。示例，`create_moment.yaml` $\rightarrow$ `CreateMomentCustomerTest.java`
2. 每条 YAML 场景对应一个同名的 JUnit 测试方法。

### 注释规范

所有注释均使用 JavaDoc 格式（`/** ... */`），严格对齐 YAML 元数据与测试职责：

| **位置**               | **注释格式**                                      | **说明**                                                     |
| ---------------------- | ------------------------------------------------- | ------------------------------------------------------------ |
| **类注释**             | `/** <YAML id> <用例集标题> */`                   | 如 `/** MOMENT-CREATE-001 发布瞬间 */`                       |
| **`@BeforeEach` 方法** | `/**\n * 【在每个测试之前】：<前置准备说明>\n */` | 如 `【在每个测试之前】：插入默认作者测试用户`                |
| **`@AfterEach` 方法**  | `/**\n * 【在每个测试之后】：<清理说明>\n */`     | 如 `【在每个测试之后】：清理测试夹具与动态发布产生的数据库测试数据` |
| **`@Test` 方法**       | `/**\n * <YAML comment 字段 对应测试项的>\n */`   | 必须与 YAML 中该用例的 `comment` 字面保持一致                |
| **私有辅助方法**       | `/**\n * 【辅助方法】：<方法职责说明>\n */`       | -                                                            |
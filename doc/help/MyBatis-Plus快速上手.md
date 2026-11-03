# MyBatis-Plus：从接入到增删改查

跟随本教程，连接 MySQL，定义一张用户表，在测试中完成新增、查询、修改和删除。随后按任务查阅条件查询、分页、字段映射和逻辑删除。

示例使用 Java 17、Spring Boot、MySQL，包名统一为 `com.example.demo`。准备一个可启动的 Spring Boot 项目，以及可访问的数据库 `demo` 和账号 `demo`，密码为 `demo`。

第一次接入，顺序阅读第 1～2 节；编写查询和更新，查第 3～4 节；配置分页和映射，查第 5～7 节；组织业务代码和排查问题，查第 8～9 节。

## 1. 理解 Entity、Mapper 和 Service

```text
Service 组织业务 → Mapper 执行数据库操作 → Entity 承载一条记录
```

Entity 是与表字段对应的 Java 对象；Mapper 是数据库读写接口。继承 `BaseMapper<Entity>`，就能调用框架提供的增删改查方法。Service 在业务开发中负责组合这些操作。

## 2. 接入并完成第一次增删改查

### 2.1 添加依赖

在 `pom.xml` 的 `<dependencies>` 中加入以下依赖。示例使用 Boot 4；Boot 3 将第一个 artifactId 换成 `mybatis-plus-spring-boot3-starter`。

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
    <version>3.5.18</version>
</dependency>

<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

前两项提供数据库访问，Lombok 用于生成实体的 getter、setter，测试依赖用于运行本节示例。已有依赖保留一份，版本管理交给 Spring Boot 和 MyBatis-Plus starter。[官方安装说明](https://baomidou.com/getting-started/install/)

### 2.2 配置数据库并创建示例表

编辑 `src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/demo
    username: demo
    password: demo
```

在 `demo` 数据库执行：

```sql
CREATE TABLE demo_user (
    id    BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(64) NOT NULL,
    age   INT NULL,
    email VARCHAR(128) NULL
);
```

数据源配置负责连接数据库，SQL 负责创建表结构。接下来用 Java 实体映射这张表。

### 2.3 创建实体

创建 `src/main/java/com/example/demo/user/User.java`：

```java
package com.example.demo.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("demo_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Integer age;
    private String email;
}
```

`@TableName` 将类映射到 `demo_user`，`@TableId` 指定自增主键，`@Data` 生成 getter、setter。字段与表中的同名列一一对应。

### 2.4 创建 Mapper

创建 `src/main/java/com/example/demo/user/UserMapper.java`：

```java
package com.example.demo.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
```

`@Mapper` 让 Spring 创建数据库访问对象，`BaseMapper<User>` 提供通用读写方法。启动类放在 `com.example.demo`，使这些类位于扫描范围内。

### 2.5 执行完整的增删改查

创建 `src/test/java/com/example/demo/UserMapperTest.java`：

```java
package com.example.demo;

import com.example.demo.user.User;
import com.example.demo.user.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UserMapperTest {
    @Autowired
    private UserMapper userMapper;

    @Test
    void crud() {
        User user = new User();
        user.setName("小明");
        user.setAge(20);
        user.setEmail("ming@example.com");

        // 新增：主键由数据库生成并回填。
        assertEquals(1, userMapper.insert(user));
        Long id = user.getId();
        assertNotNull(id);

        // 查询：按主键读取刚插入的记录。
        User loaded = userMapper.selectById(id);
        assertNotNull(loaded);
        assertEquals("小明", loaded.getName());

        // 修改：只设置本次变更的字段。
        User patch = new User();
        patch.setId(id);
        patch.setName("小红");
        assertEquals(1, userMapper.updateById(patch));
        assertEquals("小红", userMapper.selectById(id).getName());

        // 删除：移除刚插入的记录。
        assertEquals(1, userMapper.deleteById(id));
        assertNull(userMapper.selectById(id));
    }
}
```

启动 MySQL，在 IDEA 中运行测试。测试通过表示数据库连接、实体映射和四个基本操作已经串通。测试上的 `@Transactional` 会在结束时回滚本次数据变更。

这四种方法的共同规律是：传入实体或主键，Mapper 生成对应 SQL；写操作返回影响行数，查询返回实体。

## 3. 按条件查询记录

后续片段写在已注入 `UserMapper userMapper` 的业务方法中。查询构造器统一使用 Lambda 写法，通过 getter 引用字段。

### 3.1 查询列表并指定字段

```java
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

List<User> users = userMapper.selectList(
        Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getName, User::getAge)
                .ge(User::getAge, 18)
                .like(User::getName, "小")
                .orderByDesc(User::getId)
);
```

执行效果是：读取 ID、姓名和年龄，筛选年龄至少 18 岁且姓名包含“小”的记录，按 ID 降序排列。结果是 List，匹配数量可以为零或多条。

### 3.2 根据查询目标选择方法

| 查询目标 | 方法 | 返回结果 |
|---|---|---|
| 一个主键对应的记录 | `selectById(id)` | 实体或 null |
| 满足条件的多条记录 | `selectList(wrapper)` | List |
| 唯一条件对应的记录 | `selectOne(wrapper)` | 实体或 null；匹配多条时默认抛异常 |
| 满足条件的记录数量 | `selectCount(wrapper)` | Long |

例如统计成年人数：

```java
long count = userMapper.selectCount(
        Wrappers.<User>lambdaQuery().ge(User::getAge, 18)
);
```

### 3.3 让查询条件随输入变化

`keyword` 是方法参数，内容为空时省略姓名筛选：

```java
var query = Wrappers.<User>lambdaQuery()
        .like(keyword != null && !keyword.isBlank(), User::getName, keyword);

List<User> users = userMapper.selectList(query);
```

带布尔条件的重载适合处理搜索框、筛选开关等输入。每次查询创建一个 Wrapper，按输入逐项增加条件。

### 3.4 组合 AND 与 OR

```java
var query = Wrappers.<User>lambdaQuery()
        .ge(User::getAge, 18)
        .and(q -> q.eq(User::getName, "小明")
                   .or().eq(User::getName, "小红"));

List<User> users = userMapper.selectList(query);
```

对应条件为 `age >= 18 AND (name = '小明' OR name = '小红')`。`and` 中的子构造器形成一组括号。

### 3.5 查询条件速查

| 方法 | 含义 | 示例 |
|---|---|---|
| `eq`、`ne` | 等于、异于 | `.eq(User::getName, name)` |
| `gt`、`ge` | 大于、大于等于 | `.ge(User::getAge, 18)` |
| `lt`、`le` | 小于、小于等于 | `.lt(User::getAge, 30)` |
| `between` | 两端均包含的区间 | `.between(User::getAge, 18, 30)` |
| `like` | 包含关键词 | `.like(User::getName, keyword)` |
| `in` | 匹配一组值 | `.in(User::getId, ids)` |
| `isNull`、`isNotNull` | 空值、存在值 | `.isNull(User::getEmail)` |
| `orderByAsc`、`orderByDesc` | 升序、降序 | `.orderByDesc(User::getId)` |
| `select` | 指定返回列 | `.select(User::getId, User::getName)` |

完整参数和重载见 [条件构造器参考](https://baomidou.com/guides/wrapper/)。

## 4. 更新指定字段与清空字段

### 4.1 根据主键更新部分属性

```java
User patch = new User();
patch.setId(id);
patch.setName("新名字");
int affected = userMapper.updateById(patch);
```

实体更新默认忽略 null 属性，因此这个例子更新姓名，其余普通字段保持原值。

### 4.2 用 set 明确写入 NULL

清空邮箱时，使用更新构造器：

```java
int affected = userMapper.update(
        null,
        Wrappers.<User>lambdaUpdate()
                .eq(User::getId, id)
                .set(User::getName, "新名字")
                .set(User::getEmail, null)
);
```

`eq` 限定目标记录，`set` 列出本次写入的列和值。`affected` 表示影响行数，单条更新通常期望为 1。

## 5. 为列表增加分页

### 5.1 添加分页解析模块

分页解析模块使用与 starter 相同的版本：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-jsqlparser</artifactId>
    <version>3.5.18</version>
</dependency>
```

### 5.2 注册分页插件

创建 `src/main/java/com/example/demo/config/MybatisPlusConfig.java`：

```java
package com.example.demo.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

### 5.3 查询第一页

```java
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

Page<User> page = userMapper.selectPage(
        new Page<User>(1, 20),
        Wrappers.<User>lambdaQuery().orderByDesc(User::getId)
);

List<User> records = page.getRecords();
long total = page.getTotal();
long pages = page.getPages();
```

`1` 是页码，`20` 是每页条数。`records` 包含当前页数据，`total` 和 `pages` 分别提供总记录数与总页数。分页插件负责将分页条件应用到数据库查询。[分页插件参考](https://baomidou.com/plugins/pagination/)

## 6. 设置字段映射与主键策略

示例通过 `@TableName("demo_user")` 指定表名，通过 `@TableId(type = IdType.AUTO)` 对接自增主键。

假设表中的姓名列改为 `display_name`，同时增加一个仅供 Java 使用的展示属性，可分别设置：

```java
// import com.baomidou.mybatisplus.annotation.TableField;
@TableField("display_name")
private String name;

@TableField(exist = false)
private String displayText;
```

第一个属性映射到 `display_name` 列；第二个属性仅在 Java 对象中使用。默认的驼峰映射可直接将 `userName` 对应到 `user_name`。

主键策略按数据库设计选择：

| IdType | 主键来源 |
|---|---|
| `AUTO` | 数据库自增 |
| `INPUT` | 应用在插入前赋值 |
| `ASSIGN_ID` | 默认由框架生成雪花 ID |
| `ASSIGN_UUID` | 框架分配 UUID 字符串 |

修改映射时，让实体定义与真实表字段保持一致。[映射注解参考](https://baomidou.com/reference/annotation/)

## 7. 将物理删除改为逻辑删除

第 2 节的 `deleteById` 执行物理删除。要保留记录并标记删除，先增加一列：

```sql
ALTER TABLE demo_user
ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
```

然后在 `User` 中加入属性：

```java
// import com.baomidou.mybatisplus.annotation.TableLogic;
@TableLogic(value = "0", delval = "1")
private Integer deleted;
```

随后 `deleteById(id)` 将删除标记设为 1；MyBatis-Plus 生成的普通查询会筛选 `deleted = 0`。第 2 节测试中的删除结果仍表现为查询得到 null，表中则保留这条记录。

自定义 SQL 使用同样的过滤规则，在查询中添加 `deleted = 0`。[逻辑删除参考](https://baomidou.com/guides/logic-delete/)

## 8. 在业务代码中组织 Mapper 调用

### 8.1 注入 Mapper

创建 `src/main/java/com/example/demo/user/UserService.java`：

```java
package com.example.demo.user;

import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public User getById(Long id) {
        return userMapper.selectById(id);
    }
}
```

Service 可以组合多个 Mapper 调用。`IService`、`ServiceImpl` 是框架提供的另一组业务层封装，基础用法可继续使用这里的普通 Service。

### 8.2 用事务组合多个写操作

在通过 Spring Bean 从外部调用的 Service 公共方法上添加 `@Transactional`，将多个写操作放入同一事务。默认在运行时异常下回滚；检查异常也需要回滚时，配置 `rollbackFor = Exception.class`。

### 8.3 扩展自定义 SQL

在 `UserMapper` 中增加方法：

```java
@org.apache.ibatis.annotations.Select(
        "SELECT id, name, age, email FROM demo_user WHERE age >= #{age}")
java.util.List<User> findAdults(@org.apache.ibatis.annotations.Param("age") int age);
```

参数通过 `#{age}` 绑定。复杂语句可以移到 Mapper XML。启用第 7 节逻辑删除后，这条自写查询增加 `AND deleted = 0` 即可沿用相同规则。[Mapper 接口参考](https://baomidou.com/guides/data-interface/)

## 9. 按现象定位连接、映射和查询问题

| 现象 | 检查与处理 |
|---|---|
| Mapper Bean 创建失败 | 检查 `@Mapper` 与扫描包，依赖统一使用匹配 Boot 版本的 MP starter |
| 数据库连接失败 | 核对地址、端口、数据库名和账号权限 |
| 表或列相关错误 | 对照实际表结构检查表名、字段名与映射注解 |
| 自增主键为空 | 确认数据库列为自增，实体主键策略为 `AUTO` |
| 清空字段后值保持原样 | 使用第 4.2 节的 `set(字段, null)` 明确清空 |
| 分页条数与预期不同 | 确认分页解析模块和分页插件都已加载 |
| 空 ID 集合查出其他记录 | 调用 `in` 前处理空集合，直接返回空结果 |
| 更新或删除影响范围过大 | 检查主键和 Wrapper 条件；空条件会扩大操作范围 |
| getter 方法引用报错 | 确认 Lombok 注解处理生效，实体 getter 已生成 |

表结构变化可以交给 Flyway 等迁移工具管理；本文的 MyBatis-Plus 配置负责应用侧的数据访问。

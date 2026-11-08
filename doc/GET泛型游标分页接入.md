# GET 泛型游标分页接入

**这点额外代码换来的是统一、清晰的 Controller 签名，以及后续分页接口复用同一套绑定逻辑。**

本文说明如何在 GET 接口中保留 `PageRequest<MomentFeedCursor>`，通过自定义 `@PageQuery` 将查询参数绑定为分页请求。示例沿用项目现有模型，使用 Spring MVC、Jackson 3 和 Jakarta Validation；下面省略常规 import。

## 1. 方案与请求格式

`PageRequest<C>` 表达统一的分页协议，`C` 表达具体业务的分页边界。保留泛型可以让接口签名直接说明“这是分页查询，使用哪一种游标”。

当前依赖下，直接使用 `@ModelAttribute` 绑定泛型 record 的实验中，内层游标未被构造。因此使用 Spring MVC 的参数解析器：`@PageQuery` 标记需要处理的参数，解析器读取完整泛型类型，再交给 Jackson 创建对象。

```text
GET 查询参数 → 整理字段 → 按完整泛型转换 → 字段校验 → Controller
```

首屏请求省略游标，`PageRequest` 构造器将缺省数量补为 20：

```http
GET /moments/yours?limit=20
```

后续请求把上次响应的 `nextCursor` 展开为查询参数：

```http
GET /moments/yours?limit=20&cursor.memoryTime=2026-11-08T08:00:00Z&cursor.id=019a0000-0000-7000-8000-000000000001
```

URL 使用 `cursor.字段名`，Java 继续使用嵌套对象。当前实现支持游标中的普通字段，例如时间、UUID 和数字。客户端通过查询参数工具构造 URL，以完成时间等字段的编码。

## 2. 注解与参数解析器

新增 `common/request/PageQuery.java`。注解在运行时保留，供解析器识别 Controller 参数。

```java
package com.you.youbackend.common.request;

/** 标记从查询参数中解析并校验的泛型分页请求。 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PageQuery {
}
```

新增 `common/request/PageQueryArgumentResolver.java`。主方法只编排三个步骤，参数读取、类型转换和校验各自独立。

其中 `ObjectMapper` 来自 `tools.jackson.databind`，`JacksonException` 来自 `tools.jackson.core`；构造器由 Lombok 的 `@RequiredArgsConstructor` 生成。

```java
package com.you.youbackend.common.request;

/** 将查询参数转换为具有具体游标类型的分页请求。 */
@RequiredArgsConstructor
public class PageQueryArgumentResolver
        implements HandlerMethodArgumentResolver {

    private static final String CURSOR_PREFIX = "cursor.";
    private static final String REQUEST_NAME = "pageRequest";

    private final ObjectMapper objectMapper;

    /** 仅接管带有 PageQuery 注解的 PageRequest 参数。 */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(PageQuery.class)
                && parameter.getParameterType() == PageRequest.class;
    }

    /** 读取查询参数，构造具体请求，并在进入 Controller 前完成校验。 */
    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) throws Exception {

        // 按分页模型组织字段，并保留 Controller 声明的游标类型。
        Map<String, Object> values = readQueryValues(webRequest);
        PageRequest<?> request = convertRequest(values, parameter);

        // 复用模型上的校验约束，校验通过后交给 Controller。
        validateRequest(request, parameter, webRequest, binderFactory);
        return request;
    }

    /** 将 limit 与 cursor.xxx 整理为两层结构；省略游标时保留首屏语义。 */
    private Map<String, Object> readQueryValues(NativeWebRequest webRequest) {
        Map<String, Object> values = new HashMap<>();
        values.put("limit", webRequest.getParameter("limit"));

        // 收集游标字段，例如 cursor.memoryTime 转为 cursor 内的 memoryTime。
        Map<String, Object> cursor = new HashMap<>();
        webRequest.getParameterMap().forEach((name, entries) -> {
            if (name.startsWith(CURSOR_PREFIX)) {
                String field = name.substring(CURSOR_PREFIX.length());
                cursor.put(field, entries[0]);
            }
        });

        if (!cursor.isEmpty()) {
            values.put("cursor", cursor);
        }
        return values;
    }

    /** 按方法参数的完整泛型转换数据；格式错误交给现有全局异常处理。 */
    private PageRequest<?> convertRequest(
            Map<String, Object> values, MethodParameter parameter) {

        try {
            // 包含 PageRequest 与实际游标类型，并据此转换 Instant、UUID 等字段。
            var type = objectMapper.getTypeFactory()
                    .constructType(parameter.getGenericParameterType());
            return objectMapper.convertValue(values, type);
        } catch (JacksonException | IllegalArgumentException exception) {
            // 使用参数类型异常保留底层原因，由全局处理器统一返回 400。
            throw new MethodArgumentTypeMismatchException(
                    values, PageRequest.class,
                    REQUEST_NAME, parameter, exception);
        }
    }

    /** 使用 MVC 已配置的校验器，执行分页数量及内层游标的字段约束。 */
    private void validateRequest(
            PageRequest<?> request,
            MethodParameter parameter,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) throws Exception {

        // 创建绑定器只用于校验；请求对象已由 Jackson 构造完成。
        var binder = binderFactory.createBinder(
                webRequest, request, REQUEST_NAME);
        binder.validate();

        if (binder.getBindingResult().hasErrors()) {
            throw new MethodArgumentNotValidException(
                    parameter, binder.getBindingResult());
        }
    }
}
```

`readQueryValues` 产生的 Map 与模型结构对应，例如 `{"limit":"20","cursor":{"memoryTime":"…","id":"…"}}`。Map 仅作为查询参数转换的中间结构，Controller 和 Service 仍接收强类型模型。

`getGenericParameterType()` 是保留泛型的关键：它取得 `PageRequest<MomentFeedCursor>` 的完整声明，Jackson 据此创建真正的 `MomentFeedCursor`。`convertValue` 直接转换内存数据，随后调用 `PageRequest` 的构造器完成默认数量处理。

`validateRequest` 复用 Spring MVC 配置的校验器。现有 `PageRequest.cursor` 上的 `@Valid` 负责级联检查游标，因此 `@NotNull`、`@UUIDv7` 与数量约束都继续生效。这里约定 `@PageQuery` 自带校验。

## 3. 注册解析器与接入接口

新增 `common/config/WebMvcConfig.java`。Spring 注入现有 `ObjectMapper`，并将解析器加入 MVC 的参数解析流程。

```java
package com.you.youbackend.common.config;

/** 注册通用分页请求的参数解析器。 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectMapper objectMapper;

    @Override
    public void addArgumentResolvers(
            List<HandlerMethodArgumentResolver> resolvers) {

        // 将带 PageQuery 的分页请求交给专用解析器。
        resolvers.add(new PageQueryArgumentResolver(objectMapper));
    }
}
```

在现有 `MomentController` 中，将浏览接口改为 GET，并用 `@PageQuery` 替换原来的 `@Valid @RequestBody`。Service 与 Mapper 继续使用原有分页模型和查询逻辑。

```java
/** 按时间倒序浏览本人的瞬间摘要，支持游标分页。 */
@Operation(summary = "浏览自己的瞬间流")
@GetMapping("/yours")
public Result<ResultPage<MomentFeedItem, MomentFeedCursor>> browseYours(
        @PageQuery PageRequest<MomentFeedCursor> request,
        @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {

    // 委托业务层查询当前用户的分页结果。
    return Result.ok(momentService.browseYours(userId, request));
}
```

已有模型见 [PageRequest](../you-backend/src/main/java/com/you/youbackend/common/request/PageRequest.java) 和 [MomentFeedCursor](../you-backend/src/main/java/com/you/youbackend/moment/model/MomentFeedCursor.java)。格式转换失败与字段校验失败分别进入现有全局异常处理器的类型错误、校验错误方法，均返回 HTTP 400 和统一的 `Result`。

接入时同步前端和 Apifox 的请求方式、查询参数。Scalar 的参数展示由 springdoc 生成，需单独确认 `limit`、`cursor.memoryTime`、`cursor.id` 是否正确列出；运行时解析器与文档生成各自负责一部分。

## 4. 验证范围

已使用当前项目依赖进行独立 MockMvc 验证：首屏默认值、具体游标类型转换、数量越界、游标缺少必填字段、UUID 格式错误、UUIDv7 校验，以及另一种游标类型复用，均符合预期。本文记录方案与示例，业务源码尚未接入。

扩展机制参考 Spring 官方 [HandlerMethodArgumentResolver](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/method/support/HandlerMethodArgumentResolver.html)。

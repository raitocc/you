# JWT 认证：载荷、签发、解析、过滤器与登出

本文讲解 Spring MVC 应用如何使用 JWT 传递用户身份，并通过 Redis 黑名单实现登出。示例使用 Java 17、Spring Boot 4、JJWT 0.13.0，省略常规 import。

## 1. JWT 结构与载荷

常见的签名 JWT 由三部分组成：`Header.Payload.Signature`。Header 描述签名算法，Payload 是载荷，保存一组声明（Claims），Signature 用于验证前两部分的完整性。

例如，载荷可以包含：

```json
{
  "sub": "user-1001",
  "jti": "e9a876ab-a591-453c-bdce-17896674ed56",
  "iss": "demo-app",
  "iat": 1793865600,
  "exp": 1793866500
}
```

`sub` 表示用户，`jti` 表示这枚令牌，`iss` 表示签发者，`iat` 和 `exp` 分别是签发与过期时间，JSON 中的时间单位为 Unix 秒。同一个用户多次登录可以取得不同 `jti` 的令牌。

签名使服务端能够确认载荷由持有密钥的一方签发、内容保持完整。载荷本身可以被读取，因此适合保存用户标识、有效期等信息；密码保留在服务端。界面需要展示的最新昵称、头像等资料可以另行查询。[JWT 标准字段](https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1)

## 2. 依赖、密钥与有效期配置

添加下面三个 JJWT 依赖，版本均为 `0.13.0`：

| groupId | artifactId | scope |
| --- | --- | --- |
| `io.jsonwebtoken` | `jjwt-api` | 默认 compile |
| `io.jsonwebtoken` | `jjwt-impl` | runtime |
| `io.jsonwebtoken` | `jjwt-jackson` | runtime |

API 用于编写签发与解析代码，另外两个提供运行时实现及 JSON 支持。[JJWT 依赖说明](https://github.com/jwtk/jjwt/tree/0.13.0#installation)

在 `application.yaml` 配置密钥与有效期：

```yaml
jwt:
  secret: "DemoJwtSecret_ForLearningAndLocalTests_2026!"
  ttl-seconds: 900
```

下面使用 HS256，由同一个密钥签发和验证，密钥至少需要 32 字节。示例值用于本机练习，部署时配置独立的随机密钥。

创建 `JwtService`，先定义签发者、密钥和有效期。后面两节的方法都放入这个类：

```java
@Service
public class JwtService {
    private static final String ISSUER = "demo-app";
    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.ttl-seconds}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }
}
```

`Keys` 来自 `io.jsonwebtoken.security`，`SecretKey` 来自 `javax.crypto`。`ttl-seconds` 以秒为单位，构造器把它转换成 `Duration`，后续方法直接使用。

## 3. JWT 生成

登录业务先校验账号密码，成功后调用 `create(userId)`，将返回的字符串作为访问令牌交给客户端。JWT 的生成只负责把已确认的身份签成令牌。

```java
public String create(String userId) {
    Instant now = Instant.now();
    return Jwts.builder()
            .subject(userId)
            .id(UUID.randomUUID().toString())
            .issuer(ISSUER)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
}
```

这些方法依次设置 `sub`、`jti`、`iss`、`iat`、`exp`，最后签名并生成完整字符串。`Date`、`UUID` 使用 `java.util` 下的类型，`Instant`、`Duration` 使用 `java.time` 下的类型。

自定义字段可以通过 `.claim("字段名", 值)` 添加。放入令牌的字段会保留签发时的值，选择字段时考虑它需要多及时地更新。

## 4. JWT 解析与验证

请求携带 JWT 后，服务端先验证签名、签发者和时间，再读取身份。单纯解码载荷只能看到内容，认证流程使用带验证的解析 API。

先定义解析结果：

```java
public record TokenClaims(String userId, String jti, Instant expiresAt) {}
```

在 `JwtService` 中增加：

```java
public TokenClaims parse(String token) {
    Claims claims = Jwts.parser()
            .verifyWith(key)
            .requireIssuer(ISSUER)
            .build()
            .parseSignedClaims(token)
            .getPayload();

    if (claims.getSubject() == null || claims.getId() == null
            || claims.getExpiration() == null) {
        throw new JwtException("令牌缺少必要字段");
    }
    return new TokenClaims(claims.getSubject(), claims.getId(),
            claims.getExpiration().toInstant());
}
```

`Jwts`、`Claims`、`JwtException` 来自 `io.jsonwebtoken`。解析器验证签名并检查过期时间，`requireIssuer` 检查签发者；随后检查本例需要的字段是否齐全。正常结果转换成自己的 `TokenClaims`，业务代码便能使用明确类型。

令牌过期、签名错误等情况会抛出 `JwtException`，下一节把这些认证失败转成 HTTP 401。

## 5. 过滤器中的 JWT 认证

过滤器位于 Controller 之前，适合统一处理每个请求的身份认证。客户端发送：

```http
Authorization: Bearer <accessToken>
```

`OncePerRequestFilter` 提供 `doFilterInternal` 作为处理入口。`shouldNotFilter` 决定哪些请求跳过认证；`chain.doFilter` 把成功的请求交给后续处理，最终进入 Controller。

下面的过滤器保护业务接口，公开注册、登录和错误入口。`@Component` 使 Spring Boot 将它注册到 Servlet 请求链，`JwtService` 通过构造器注入：

```java
@Component
public class JwtFilter extends OncePerRequestFilter {
    public static final String CURRENT_TOKEN = "currentToken";
    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtService jwtService;

    public JwtFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return "OPTIONS".equals(request.getMethod())
                || path.equals("/auth/register")
                || path.equals("/auth/login")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        try {
            String header = request.getHeader("Authorization");
            if (!StringUtils.startsWithIgnoreCase(header, BEARER_PREFIX)
                    || !StringUtils.hasText(header.substring(BEARER_PREFIX.length()))) {
                throw new JwtException("请提供 Bearer 令牌");
            }
            String token = header.substring(BEARER_PREFIX.length()).trim();
            TokenClaims claims = jwtService.parse(token);
            request.setAttribute(CURRENT_TOKEN, claims);
        } catch (JwtException exception) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"登录凭证无效或已失效\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
```

`OncePerRequestFilter` 来自 `org.springframework.web.filter`，请求、响应、过滤链类型来自 `jakarta.servlet`，`StringUtils` 来自 `org.springframework.util`。

执行顺序是“提取令牌 → 验签并解析 → 写入请求属性 → 继续过滤链”。认证失败时直接返回 401，请求在过滤器结束；认证成功后，Controller 才会执行。

过滤器位于 MVC 调度之前，示例在 catch 中直接输出 JSON。沿用统一响应格式时，可以用 `ObjectMapper` 写出自己的响应对象，或显式委托 `HandlerExceptionResolver` 处理异常；`@RestControllerAdvice` 的默认作用范围是 MVC 内部。

## 6. Controller 获取载荷

`request.setAttribute` 将已验证的身份附在本次请求上，Controller 通过相同属性名读取。这些属性只存在于服务端当前请求，客户端继续只传 Bearer 令牌。

```java
@GetMapping("/me")
public ProfileResponse me(@RequestAttribute(JwtFilter.CURRENT_TOKEN) TokenClaims claims) {
    return new ProfileResponse(claims.userId());
}

public record ProfileResponse(String userId) {}
```

这个方法放入 `@RestController`。`@RequestAttribute` 取过滤器写入的对象，`claims.userId()` 就是验证后的用户标识；查询数据库和对象操作权限继续由业务 Service 判断。

## 7. Redis 黑名单与登出

JWT 中的 `exp` 决定自然过期时间。用户提前退出时，服务端把当前 `jti` 加入黑名单，后续请求在验签后查询它，从而使这枚令牌提前失效。

添加 `org.springframework.boot:spring-boot-starter-data-redis`，并连接已有 Redis：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: demo
```

Spring 提供 `StringRedisTemplate`，适合操作这里的字符串 Key。将读写放在同一个服务中，共用 Key 前缀：

```java
@Service
public class TokenBlacklist {
    private static final String KEY_PREFIX = "demo:auth:revoked:";
    private final StringRedisTemplate redis;

    public TokenBlacklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean contains(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
    }

    public void revoke(TokenClaims claims) {
        long millis = claims.expiresAt().toEpochMilli() - System.currentTimeMillis();
        if (millis > 0) {
            redis.opsForValue().set(KEY_PREFIX + claims.jti(), "1",
                    Duration.ofMillis(millis));
        }
    }
}
```

TTL 是当前令牌剩余有效期，过期记录由 Redis 自动清理。黑名单按 `jti` 区分令牌，因此同一用户在另一端取得的令牌继续有效。

在过滤器中增加 `TokenBlacklist` 的构造器注入，将下面的检查放在 `parse` 之后、`request.setAttribute` 之前：

```java
if (blacklist.contains(claims.jti())) {
    throw new JwtException("令牌已撤销");
}
```

退出接口同样注入 `TokenBlacklist`，取得过滤器保存的载荷并撤销：

```java
@PostMapping("/auth/logout")
public ResponseEntity<Void> logout(
        @RequestAttribute(JwtFilter.CURRENT_TOKEN) TokenClaims claims) {
    blacklist.revoke(claims);
    return ResponseEntity.noContent().build();
}
```

这个接口返回 HTTP 204，客户端随后清除本地凭据。整个流程为“退出请求通过认证 → 写黑名单 → 后续请求查到黑名单并返回 401”。可以用同一用户的 A、B 两枚令牌验证：A 退出后访问失败，B 仍正常。[撤销机制说明](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html#jwt)

项目中的实际接口、统一响应和过滤器接法见 [后端开发约定](../YOU-后端开发约定.md)。

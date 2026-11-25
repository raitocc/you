package com.you.youbackend.user.filter;

import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.user.model.AccessTokenClaims;
import com.you.youbackend.user.service.JwtService;
import com.you.youbackend.user.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 请求入口验访问令牌：合法就把当前用户身份和令牌信息写进请求属性，非法交全局异常处理器返回 401。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 请求属性名：当前用户 ID（UUID），业务层用它做归属校验。 */
    public static final String CURRENT_USER_ID = "currentUserId";

    /** 请求属性名：已验证的令牌信息（AccessTokenClaims），logout 等场景取 jti 用。 */
    public static final String CURRENT_ACCESS_TOKEN = "currentAccessToken";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final HandlerExceptionResolver exceptionResolver;

    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthFilter(
            JwtService jwtService,
            @Qualifier("handlerExceptionResolver")
            HandlerExceptionResolver exceptionResolver,
            TokenBlacklistService tokenBlacklistService) {

        this.jwtService = jwtService;
        this.exceptionResolver = exceptionResolver;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * 注册、登录和接口文档公开访问，其余业务接口验证身份。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        return "OPTIONS".equals(request.getMethod())
                || path.equals("/auth/register")
                || path.equals("/auth/login")
                || path.equals("/scalar")
                || path.startsWith("/scalar/")
                || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/")
                || path.startsWith("/webjars/")
                || path.equals("/error");
    }

    /**
     * 取 Authorization 头里的 Bearer 令牌，验签、查黑名单，通过后把用户 ID 和令牌写进请求属性。
     *
     * <p>副作用：往 request 写 CURRENT_USER_ID、CURRENT_ACCESS_TOKEN 两个属性。
     * 认证失败不抛给容器，改为设置 WWW-Authenticate 头并交给全局异常处理器渲染 401。</p>
     *
     * @throws ServletException 非业务异常向上传递
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        try {
            // 1. 取 Authorization 头，没带 Bearer 前缀一律按未登录处理
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

            if (!StringUtils.startsWithIgnoreCase(
                    authorization, BEARER_PREFIX)) {

                throw BusinessException.unauthorized("请先登录");
            }

            // 2. 验签解析，拿到用户 ID 和令牌编号
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            AccessTokenClaims claims = jwtService.parseAccessToken(token);
            // 3. 查黑名单，已退出登录的令牌立即失效
            if (tokenBlacklistService.isRevoked(claims.jti())) {
                throw BusinessException.unauthorized("登录状态已失效，请重新登录");
            }

            // 4. 把身份写进请求属性，业务层按需取用
            request.setAttribute(CURRENT_USER_ID, claims.userId());
            request.setAttribute(CURRENT_ACCESS_TOKEN, claims);

        } catch (BusinessException exception) {
            // 认证失败不抛给容器，设 WWW-Authenticate 头后交全局异常处理器渲染 401
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            exceptionResolver.resolveException(
                    request, response, null, exception);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
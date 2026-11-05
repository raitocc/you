package com.you.youbackend.user.filter;

import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.user.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 验证访问令牌，将当前用户身份写入请求。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER_ID = "currentUserId";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final HandlerExceptionResolver exceptionResolver;

    public JwtAuthFilter(
            JwtService jwtService,
            @Qualifier("handlerExceptionResolver")
            HandlerExceptionResolver exceptionResolver) {

        this.jwtService = jwtService;
        this.exceptionResolver = exceptionResolver;
    }

    /**
     * 注册、登录和接口文档公开访问，其余业务接口验证身份。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        return "OPTIONS".equals(request.getMethod())
                || ("HEAD".equals(request.getMethod()) && path.equals("/"))
                || ("POST".equals(request.getMethod()) && path.equals("/media/uploads/callback"))
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
     * 认证成功后继续请求，认证失败交给全局异常处理器。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        try {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

            if (!StringUtils.startsWithIgnoreCase(
                    authorization, BEARER_PREFIX)) {

                throw new BusinessException(
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED",
                        "请先登录");
            }

            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            UUID userId = jwtService.parseUserId(token);
            request.setAttribute(CURRENT_USER_ID, userId);
        } catch (BusinessException exception) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            exceptionResolver.resolveException(
                    request, response, null, exception);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
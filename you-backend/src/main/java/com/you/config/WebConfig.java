package com.you.config;

import com.you.common.exception.BusinessException;
import com.you.user.mapper.UserMapper;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final JwtService jwt;
  private final UserMapper users;

  public WebConfig(JwtService jwt, UserMapper users) {
    this.jwt = jwt;
    this.users = users;
  }

  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              public boolean preHandle(
                  HttpServletRequest r, HttpServletResponse response, Object handler) {
                if (r.getMethod().equals("POST")
                    && (r.getRequestURI().equals("/user/users")
                        || r.getRequestURI().equals("/user/sessions"))) return true;
                String h = r.getHeader("Authorization");
                if (h == null || !h.startsWith("Bearer ")) throw BusinessException.unauthorized();
                var id = jwt.verify(h.substring(7));
                if (users.find(id) == null) throw BusinessException.unauthorized();
                r.setAttribute("userId", id);
                return true;
              }
            })
        .addPathPatterns("/user/**", "/media/**");
  }
}

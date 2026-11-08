package com.you.youbackend.common.config;

import com.you.youbackend.common.request.PageQueryArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

/** 注册 GET 泛型分页参数解析器，复用应用的 Jackson 配置。 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final ObjectMapper objectMapper;

    /** 将 PageQuery 参数接入 MVC 的查询参数解析流程。 */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        // 仅接管显式标注的分页参数，转换及校验规则集中维护。
        resolvers.add(new PageQueryArgumentResolver(objectMapper));
    }
}

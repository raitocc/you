package com.you.youbackend.common.request;

import com.you.youbackend.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/** 将 limit、cursor.xxx 查询参数转换为保留具体游标类型的分页请求，复用 MVC 校验器。 */
@RequiredArgsConstructor
public class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {
    private static final String CURSOR_PREFIX = "cursor.";
    private static final String REQUEST_NAME = "pageRequest";
    private final ObjectMapper objectMapper;

    /** 仅接管显式标注 PageQuery 的分页参数，不影响普通请求对象。 */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        // 同时检查注解与分页模型，避免改变其他 Controller 参数的绑定方式。
        return parameter.hasParameterAnnotation(PageQuery.class) && parameter.getParameterType() == PageRequest.class;
    }

    /** 读取查询参数、按完整泛型转换并校验，通过后才进入 Controller。 */
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        // 1. 将平铺查询参数整理为 PageRequest 对应的两层结构。
        Map<String, Object> values = readQueryValues(webRequest);
        // 2. 保留 Controller 声明的具体游标类型，交给 Jackson 转换 UUID、时间等字段。
        PageRequest<?> request = convertRequest(values, parameter);
        // 3. 使用 MVC 校验器触发分页限制和游标嵌套约束。
        validateRequest(request, parameter, webRequest, binderFactory);
        return request;
    }

    /** 收集 limit 与一层 cursor.xxx 字段；无游标字段时保留首屏语义。 */
    private Map<String, Object> readQueryValues(NativeWebRequest webRequest) {
        // 1. 不接受字符串游标，避免错误格式被当成首屏静默处理。
        if (webRequest.getParameterMap().containsKey("cursor")) {
            throw BusinessException.invalidArgument("请使用 cursor.字段名 提交游标");
        }
        Map<String, Object> values = new HashMap<>();
        values.put("limit", webRequest.getParameter("limit"));
        // 2. 将 cursor.memoryTime 等字段放入游标对象，缺少必填项由模型校验处理。
        Map<String, Object> cursor = new HashMap<>();
        webRequest.getParameterMap().forEach((name, entries) -> {
            if (name.startsWith(CURSOR_PREFIX)) {
                cursor.put(name.substring(CURSOR_PREFIX.length()), entries[0]);
            }
        });
        if (!cursor.isEmpty()) {
            values.put("cursor", cursor);
        }
        return values;
    }

    /** 按方法参数完整泛型构造分页请求，转换错误保留原因并交给统一参数异常处理。 */
    private PageRequest<?> convertRequest(Map<String, Object> values, MethodParameter parameter) {
        // 使用完整泛型声明，确保内层生成业务游标而不是动态 Map。
        try {
            var type = objectMapper.getTypeFactory().constructType(parameter.getGenericParameterType());
            return objectMapper.convertValue(values, type);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new MethodArgumentTypeMismatchException(values, PageRequest.class, REQUEST_NAME, parameter, exception);
        }
    }

    /** 对已构造的分页对象执行 MVC 字段校验，失败沿用统一 INVALID_ARGUMENT 响应。 */
    private void validateRequest(PageRequest<?> request, MethodParameter parameter, NativeWebRequest webRequest,
                                 WebDataBinderFactory binderFactory) throws Exception {
        // 创建绑定器仅执行校验，PageRequest.cursor 上的 Valid 负责级联游标字段。
        var binder = binderFactory.createBinder(webRequest, request, REQUEST_NAME);
        binder.validate();
        if (binder.getBindingResult().hasErrors()) {
            throw new MethodArgumentNotValidException(parameter, binder.getBindingResult());
        }
    }
}

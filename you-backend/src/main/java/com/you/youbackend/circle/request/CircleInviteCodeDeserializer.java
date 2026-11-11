package com.you.youbackend.circle.request;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 按 JSON 整数读取圈子邀请码，避免浮点数截断或字符串隐式转换。 */
public class CircleInviteCodeDeserializer extends ValueDeserializer<Long> {
    /** 非整数由统一异常处理返回参数错误，最小值由请求约束校验。 */
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        // 先检查 JSON 类型，再读取 64 位整数。
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
        return parser.getLongValue();
    }
}

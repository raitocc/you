package com.you.youbackend.common.util;

import java.security.SecureRandom;

/** 生成同行与圈子共用的长期邀请码；保存及唯一性约束由各业务负责。 */
public final class InviteCodeGenerator {
    /** 与数据库 VARCHAR(16) 对齐的固定生成长度。 */
    public static final int LENGTH = 16;
    /** 大写字母和数字，保持与数据库忽略大小写的比较方式一致。 */
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCodeGenerator() {
    }

    /**
     * 从统一字符集中随机生成长期邀请码。
     * @return 16 位大写字母与数字字符串；数据库唯一索引负责最终去重
     */
    public static String generate() {
        // 逐位随机选择字符，面对面短码继续由各自的业务流程管理。
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return code.toString();
    }
}

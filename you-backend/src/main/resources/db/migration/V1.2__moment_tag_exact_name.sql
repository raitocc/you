-- 标签仅在去除首尾空白后按实际字符判重。
ALTER TABLE moment_tag
    MODIFY tag_name VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '标签名称，非空白，同一瞬间内唯一';

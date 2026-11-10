-- 圈子系统头像不登记为用户文件，仅自定义头像保存媒体文件引用。
ALTER TABLE circle ADD COLUMN avatar_file_id BINARY(16) NULL COMMENT '自定义头像文件 ID；null 使用系统默认头像';
-- 匹配本人圈子按成员关系创建时间及 ID 倒序的分页。
CREATE INDEX idx_circle_member_user_page ON circle_member (user_id, deleted_at, created_at, id);

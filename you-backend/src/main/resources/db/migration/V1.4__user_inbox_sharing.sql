-- 信箱表当前为空；分享时间表达实际业务行为，圈子分享引用用于共享详情导航。
ALTER TABLE user_inbox
    ADD COLUMN shared_at DATETIME(3) NOT NULL COMMENT '关联或分享实际发生时间，UTC' AFTER object_id,
    ADD COLUMN share_id BINARY(16) NULL COMMENT '圈子分享关系 ID，同行动态为空' AFTER shared_at,
    DROP INDEX idx_user_inbox_recipient_feed,
    ADD INDEX idx_user_inbox_recipient_feed (recipient_user_id, deleted_at, shared_at, id),
    ADD INDEX idx_user_inbox_share (share_id, deleted_at),
    ADD INDEX idx_user_inbox_source_object (source_type, source_id, object_id, deleted_at);

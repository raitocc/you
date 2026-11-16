ALTER TABLE circle_member
    DROP INDEX idx_circle_member_user_page,
    ADD INDEX idx_circle_member_user_page (user_id, deleted_at, created_at, circle_id);

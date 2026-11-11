CREATE TABLE circle_invite_number (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '圈子邀请码，从 10000 开始分配',
    circle_id BINARY(16) NOT NULL COMMENT '获发该邀请码的圈子 ID',
    PRIMARY KEY (id),
    KEY idx_circle_invite_number_circle (circle_id)
) ENGINE=InnoDB AUTO_INCREMENT=10000 COMMENT='圈子邀请码分配记录';

ALTER TABLE circle ADD COLUMN numeric_invite_code BIGINT NULL;

INSERT INTO circle_invite_number (circle_id)
SELECT id FROM circle ORDER BY created_at, id;

UPDATE circle AS c
JOIN circle_invite_number AS n ON n.circle_id = c.id
SET c.numeric_invite_code = n.id;

ALTER TABLE circle
    DROP INDEX uk_circle_invite_code,
    DROP COLUMN invite_code,
    CHANGE COLUMN numeric_invite_code invite_code BIGINT NOT NULL COMMENT '自增数字邀请码，可重置',
    ADD UNIQUE KEY uk_circle_invite_code (invite_code);

ALTER TABLE circle_member
    ADD COLUMN join_restricted TINYINT NOT NULL DEFAULT 0 COMMENT '是否限制该用户再次加入圈子';

-- 永久记录首次面对面短码；不同同行允许使用相同短码。
ALTER TABLE gathering
    ADD COLUMN face_code CHAR(4) NULL COMMENT '首次开启的面对面短码，永久保留' AFTER invite_code;
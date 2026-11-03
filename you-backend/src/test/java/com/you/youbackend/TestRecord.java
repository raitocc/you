package com.you.youbackend;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("test")
public class TestRecord {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
}

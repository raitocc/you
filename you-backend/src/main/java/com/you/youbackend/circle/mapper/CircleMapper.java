package com.you.youbackend.circle.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.Circle;
import org.apache.ibatis.annotations.*;
import java.util.UUID;
/** 圈子读写；自定义查询显式声明软删除范围。 */
@Mapper
public interface CircleMapper extends BaseMapper<Circle> {
    /** 包含解散记录以识别被占用的客户端 ID，创建时直接拒绝重复提交。 */
    @Select("SELECT * FROM circle WHERE id = #{id}")
    Circle selectIncludingDeleted(@Param("id") UUID id);

    /** 显式更新可空字段，保留创建者、邀请信息及创建时间。 */
    default int updateContent(Circle circle) {
        // null 必须写入数据库以清空介绍和自定义头像。
        return update(Wrappers.<Circle>lambdaUpdate().eq(Circle::getId, circle.getId())
                .eq(Circle::getCreatorUserId, circle.getCreatorUserId())
                .set(Circle::getName, circle.getName()).set(Circle::getDescription, circle.getDescription())
                .set(Circle::getAvatarFileId, circle.getAvatarFileId()).set(Circle::getUpdatedAt, circle.getUpdatedAt()));
    }
}

package com.you.youbackend.circle.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.you.youbackend.circle.entity.CircleInviteNumber;
import org.apache.ibatis.annotations.Mapper;
import java.util.UUID;

@Mapper
public interface CircleInviteNumberMapper extends BaseMapper<CircleInviteNumber> {
    /** 为指定圈子分配新的邀请码，分配记录参与调用方事务。 */
    default long allocate(UUID circleId) {
        // MP 插入后将自增主键回填到记录。
        var number = new CircleInviteNumber();
        number.setCircleId(circleId);
        insert(number);
        return number.getId();
    }
}

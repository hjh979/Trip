package com.zkry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkry.domain.entity.TripItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface TripItemMapper extends BaseMapper<TripItem> {

    @Delete("DELETE FROM trip_item WHERE day_id IN (SELECT id FROM trip_day WHERE plan_id = #{planId})")
    int deletePhysicallyByPlanId(Long planId);
}

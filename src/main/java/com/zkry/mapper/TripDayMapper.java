package com.zkry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkry.domain.entity.TripDay;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface TripDayMapper extends BaseMapper<TripDay> {

    @Delete("DELETE FROM trip_day WHERE plan_id = #{planId}")
    int deletePhysicallyByPlanId(Long planId);
}

package com.zkry.memory.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkry.memory.domain.TripMemoryEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TripMemoryEventMapper extends BaseMapper<TripMemoryEvent> { }

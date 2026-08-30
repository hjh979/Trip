package com.zkry.memory.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkry.memory.domain.UserMemoryFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface UserMemoryFactMapper extends BaseMapper<UserMemoryFact> {
    @Delete("DELETE FROM user_memory_fact WHERE id = #{id} AND user_id = #{userId}")
    int hardDelete(Long id, Long userId);

    @Delete("DELETE FROM user_memory_fact WHERE user_id = #{userId}")
    int hardDeleteAll(Long userId);
}

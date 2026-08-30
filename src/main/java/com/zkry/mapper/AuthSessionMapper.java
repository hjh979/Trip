package com.zkry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkry.domain.entity.AuthSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSession> {
}

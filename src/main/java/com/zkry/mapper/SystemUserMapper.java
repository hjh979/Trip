package com.zkry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkry.domain.entity.SystemUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SystemUserMapper extends BaseMapper<SystemUser> {
}

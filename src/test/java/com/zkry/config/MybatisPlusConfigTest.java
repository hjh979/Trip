package com.zkry.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

class MybatisPlusConfigTest {

    @Test
    void registersOptimisticLockBeforePagination() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();

        assertInstanceOf(
            OptimisticLockerInnerInterceptor.class,
            interceptor.getInterceptors().get(0)
        );
        assertInstanceOf(
            PaginationInnerInterceptor.class,
            interceptor.getInterceptors().get(1)
        );
    }
}

package com.zkry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkry.domain.entity.MessageOutbox;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MessageOutboxMapper extends BaseMapper<MessageOutbox> {

    @Update("""
        UPDATE message_outbox
        SET status = 'publishing',
            claim_token = #{claimToken},
            claim_until = #{claimUntil},
            publish_attempts = publish_attempts + 1,
            update_time = #{now}
        WHERE id = #{id}
          AND deleted = 0
          AND status IN ('pending', 'failed')
          AND next_attempt_at <= #{now}
          AND (claim_until IS NULL OR claim_until < #{now})
        """)
    int claim(
        @Param("id") Long id,
        @Param("claimToken") String claimToken,
        @Param("claimUntil") LocalDateTime claimUntil,
        @Param("now") LocalDateTime now
    );
}

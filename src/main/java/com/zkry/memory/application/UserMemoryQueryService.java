package com.zkry.memory.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.memory.domain.MemoryContextPack;
import com.zkry.memory.domain.MemoryStatus;
import com.zkry.memory.domain.UserMemoryFact;
import com.zkry.memory.infrastructure.UserMemoryFactMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UserMemoryQueryService {
    private final UserMemoryFactMapper mapper;
    private final MemoryPolicyResolver resolver;
    public UserMemoryQueryService(UserMemoryFactMapper mapper, MemoryPolicyResolver resolver) {
        this.mapper = mapper; this.resolver = resolver;
    }
    public List<UserMemoryFact> list(Long userId, boolean includeCandidates) {
        if (userId == null) return List.of();
        return mapper.selectList(Wrappers.<UserMemoryFact>lambdaQuery().eq(UserMemoryFact::getUserId, userId)
            .in(includeCandidates, UserMemoryFact::getStatus, List.of(MemoryStatus.ACTIVE.name(), MemoryStatus.CANDIDATE.name()))
            .eq(!includeCandidates, UserMemoryFact::getStatus, MemoryStatus.ACTIVE.name())
            .and(q -> q.isNull(UserMemoryFact::getExpiresAt).or().gt(UserMemoryFact::getExpiresAt, LocalDateTime.now()))
            .orderByDesc(UserMemoryFact::getHardConstraint).orderByDesc(UserMemoryFact::getConfidence));
    }
    public MemoryContextPack context(Long userId, String city, String planId) {
        return resolver.resolve(list(userId, false), city, planId);
    }
}

package com.zkry.memory.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.common.util.JsonUtils;
import com.zkry.memory.domain.MemoryScope;
import com.zkry.memory.domain.MemorySource;
import com.zkry.memory.domain.MemoryStatus;
import com.zkry.memory.domain.MemoryType;
import com.zkry.memory.domain.UserMemoryFact;
import com.zkry.memory.infrastructure.UserMemoryFactMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserMemoryCommandService {
    private final UserMemoryFactMapper mapper;
    public UserMemoryCommandService(UserMemoryFactMapper mapper) { this.mapper = mapper; }

    @Transactional
    public UserMemoryFact createExplicit(Long userId, MemoryInput input) {
        validate(input);
        String fingerprint = fingerprint(input);
        UserMemoryFact fact = mapper.selectOne(Wrappers.<UserMemoryFact>lambdaQuery()
            .eq(UserMemoryFact::getUserId, userId).eq(UserMemoryFact::getMemoryFingerprint, fingerprint).last("LIMIT 1"));
        if (fact == null) { fact = new UserMemoryFact(); fact.setUserId(userId); fact.setFirstSeenAt(LocalDateTime.now()); }
        apply(fact, input, MemorySource.EXPLICIT.name(), MemoryStatus.ACTIVE.name(), 1D);
        if (fact.getId() == null) mapper.insert(fact); else mapper.updateById(fact);
        return fact;
    }
    @Transactional
    public UserMemoryFact update(Long userId, Long id, MemoryInput input) {
        UserMemoryFact fact = owned(userId, id); validate(input);
        apply(fact, input, fact.getSource(), fact.getStatus(), fact.getConfidence()); mapper.updateById(fact); return fact;
    }
    @Transactional
    public UserMemoryFact confirm(Long userId, Long id) {
        UserMemoryFact fact = owned(userId, id); fact.setStatus(MemoryStatus.ACTIVE.name()); fact.setSource(MemorySource.CONFIRMED.name());
        fact.setConfidence(1D); fact.setLastConfirmedAt(LocalDateTime.now()); mapper.updateById(fact); return fact;
    }
    @Transactional
    public void reject(Long userId, Long id) { changeStatus(owned(userId, id), MemoryStatus.DELETED.name()); }
    @Transactional
    public void delete(Long userId, Long id) { mapper.hardDelete(owned(userId, id).getId(), userId); }
    @Transactional
    public void deleteAll(Long userId) { mapper.hardDeleteAll(userId); }
    public void upsertCandidate(Long userId, MemoryInput input, double confidence, List<String> evidence) {
        validate(input); String fp = fingerprint(input); UserMemoryFact fact = mapper.selectOne(Wrappers.<UserMemoryFact>lambdaQuery()
            .eq(UserMemoryFact::getUserId, userId).eq(UserMemoryFact::getMemoryFingerprint, fp).last("LIMIT 1"));
        if (fact != null) return;
        fact = new UserMemoryFact(); fact.setUserId(userId); fact.setFirstSeenAt(LocalDateTime.now());
        input = new MemoryInput(input.memoryType(), input.memoryKey(), input.memoryValue(), input.scopeType(), input.scopeValue(), false, evidence);
        apply(fact, input, MemorySource.INFERRED.name(), MemoryStatus.CANDIDATE.name(), confidence); mapper.insert(fact);
    }
    private void apply(UserMemoryFact fact, MemoryInput input, String source, String status, Double confidence) {
        fact.setMemoryType(MemoryType.valueOf(input.memoryType()).name()); fact.setMemoryKey(input.memoryKey().trim());
        fact.setMemoryValueJson(JsonUtils.toJsonString(input.memoryValue())); fact.setScopeType(MemoryScope.valueOf(normalizedScope(input)).name());
        fact.setScopeValue(input.scopeValue() == null ? "" : input.scopeValue().trim()); fact.setHardConstraint(Boolean.TRUE.equals(input.hardConstraint()));
        fact.setEvidenceRefsJson(JsonUtils.toJsonString(input.evidenceRefs() == null ? List.of() : input.evidenceRefs()));
        fact.setMemoryFingerprint(fingerprint(input)); fact.setSource(source); fact.setStatus(status); fact.setConfidence(confidence == null ? 1D : confidence); fact.setLastObservedAt(LocalDateTime.now());
    }
    private UserMemoryFact owned(Long userId, Long id) { UserMemoryFact f = mapper.selectById(id); if (f == null || !userId.equals(f.getUserId())) throw new BizException("用户记忆不存在或无权访问"); return f; }
    private void changeStatus(UserMemoryFact fact, String status) { fact.setStatus(status); mapper.updateById(fact); }
    private void validate(MemoryInput input) {
        if (input == null || input.memoryType() == null || input.memoryKey() == null || input.memoryKey().isBlank()) throw new BizException("记忆类型和键不能为空");
        try { MemoryType.valueOf(input.memoryType()); MemoryScope.valueOf(normalizedScope(input)); } catch (IllegalArgumentException e) { throw new BizException("不支持的记忆类型或作用域"); }
        if (MemoryType.ACCESSIBILITY.name().equals(input.memoryType()) && JsonUtils.toJsonString(input.memoryValue()).matches(".*(疾病|病史|诊断|病名).*")) throw new BizException("无障碍记忆只能保存功能性旅行约束");
    }
    private String fingerprint(MemoryInput input) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((input.memoryType()+"|"+input.memoryKey()+"|"+normalizedScope(input)+"|"+input.scopeValue()+"|"+JsonUtils.toJsonString(input.memoryValue())).getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String normalizedScope(MemoryInput input) { return input.scopeType() == null || input.scopeType().isBlank() ? "GLOBAL" : input.scopeType(); }
    public record MemoryInput(String memoryType, String memoryKey, Object memoryValue, String scopeType,
                              String scopeValue, Boolean hardConstraint, List<String> evidenceRefs) { }
}

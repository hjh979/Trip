package com.zkry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.domain.entity.AuditLog;
import com.zkry.domain.vo.AuditLogView;
import com.zkry.mapper.AuditLogMapper;
import com.zkry.security.VoyagePrincipal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AuditLogMapper mapper;

    public AuditLogService(AuditLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(VoyagePrincipal principal, String action, String detail, String source, String result) {
        AuditLog log = new AuditLog();
        log.setActorUserId(principal == null ? 0L : principal.userId());
        log.setActorName(principal == null ? "系统" : principal.username());
        log.setAction(action);
        log.setDetail(detail == null ? "" : detail);
        log.setSource(source);
        log.setResult(result);
        mapper.insert(log);
    }

    public List<AuditLogView> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return mapper.selectList(Wrappers.<AuditLog>lambdaQuery().orderByDesc(AuditLog::getCreateTime)
            .last("LIMIT " + safeLimit)).stream().map(log -> new AuditLogView(
                log.getId(), log.getCreateTime() == null ? "" : TIME.format(log.getCreateTime()),
                log.getActorName(), log.getAction(), log.getDetail(), log.getSource(), log.getResult()
            )).toList();
    }
}

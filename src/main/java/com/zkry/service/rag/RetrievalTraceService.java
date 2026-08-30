package com.zkry.service.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.entity.RetrievalTrace;
import com.zkry.domain.vo.RagCitationView;
import com.zkry.mapper.RetrievalTraceMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RetrievalTraceService {

    private final RetrievalTraceMapper mapper;

    public RetrievalTraceService(RetrievalTraceMapper mapper) {
        this.mapper = mapper;
    }

    public String save(
        String query,
        Map<String, Object> filters,
        String mode,
        Map<String, List<RagCitationView>> candidates,
        List<RagCitationView> selected,
        long latencyMs
    ) {
        RetrievalTrace trace = new RetrievalTrace();
        trace.setTraceId(UUID.randomUUID().toString());
        trace.setQueryText(query);
        trace.setFilterJson(JsonUtils.toJsonString(filters));
        trace.setRetrievalMode(mode);
        trace.setCandidatesJson(JsonUtils.toJsonString(candidates));
        trace.setFinalCitationsJson(JsonUtils.toJsonString(selected));
        trace.setLatencyMs(latencyMs);
        trace.setAdopted(null);
        mapper.insert(trace);
        return trace.getTraceId();
    }

    public void markAdopted(String traceId, boolean adopted) {
        int changed = mapper.update(
            null,
            Wrappers.<RetrievalTrace>lambdaUpdate()
                .eq(RetrievalTrace::getTraceId, traceId)
                .set(RetrievalTrace::getAdopted, adopted)
        );
        if (changed != 1) throw new IllegalArgumentException("Retrieval trace not found");
    }
}

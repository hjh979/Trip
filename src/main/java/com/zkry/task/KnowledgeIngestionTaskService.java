package com.zkry.task;

import com.zkry.domain.dto.knowledge.IndexKnowledgeDocumentRequest;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.vo.SubmitTripPlanResponse;
import com.zkry.security.VoyagePrincipal;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIngestionTaskService {

    private final TripTaskStore taskStore;

    public KnowledgeIngestionTaskService(TripTaskStore taskStore) {
        this.taskStore = taskStore;
    }

    public SubmitTripPlanResponse submit(IndexKnowledgeDocumentRequest request, VoyagePrincipal principal) {
        if (principal == null) throw new IllegalStateException("Authenticated principal is required");
        TripTask task = taskStore.create(
            principal.userId(),
            TaskType.KNOWLEDGE_INGESTION,
            request,
            "知识文档已进入后台索引队列"
        );
        return new SubmitTripPlanResponse(
            task.getTaskId(), task.getTaskId(), task.getStatus(), task.getProgressText()
        );
    }
}

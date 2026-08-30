package com.zkry.task;

import com.zkry.domain.dto.knowledge.SyncTravelCorpusRequest;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.vo.SubmitTripPlanResponse;
import com.zkry.security.VoyagePrincipal;
import org.springframework.stereotype.Service;

/** Queues bulk corpus synchronization so HTTP threads never perform a long crawl. */
@Service
public class KnowledgeCorpusSyncTaskService {

    private final TripTaskStore taskStore;

    public KnowledgeCorpusSyncTaskService(TripTaskStore taskStore) {
        this.taskStore = taskStore;
    }

    public SubmitTripPlanResponse submit(
        SyncTravelCorpusRequest request,
        VoyagePrincipal principal
    ) {
        if (principal == null) throw new IllegalStateException("Authenticated principal is required");
        SyncTravelCorpusRequest safeRequest = request == null
            ? new SyncTravelCorpusRequest(java.util.List.of(), true)
            : request;
        TripTask task = taskStore.create(
            principal.userId(),
            TaskType.KNOWLEDGE_CORPUS_SYNC,
            safeRequest,
            "开放旅行语料同步已进入后台低优先级队列"
        );
        return new SubmitTripPlanResponse(
            task.getTaskId(), task.getTaskId(), task.getStatus(), task.getProgressText()
        );
    }
}

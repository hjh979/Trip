package com.zkry.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zkry.domain.entity.TripTask;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LocalTaskDispatcherTest {

    @Test
    void claimsAndRoutesPlanWithoutRabbit() {
        TripTaskStore store = mock(TripTaskStore.class);
        TripPlanningWorkflow planning = mock(TripPlanningWorkflow.class);
        AiModificationWorkflow modification = mock(AiModificationWorkflow.class);
        KnowledgeIngestionWorkflow knowledge = mock(KnowledgeIngestionWorkflow.class);
        TaskFailureClassifier classifier = mock(TaskFailureClassifier.class);
        TaskRealtimePublisher publisher = mock(TaskRealtimePublisher.class);
        ExecutorService executor = mock(ExecutorService.class);
        ScheduledExecutorService heartbeatExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);

        TripTask queued = task("task-local-plan", TaskType.TRIP_PLAN);
        TripTask claimed = task("task-local-plan", TaskType.TRIP_PLAN);
        claimed.setAttempt(1);
        when(store.dueTasks(anyInt())).thenReturn(List.of(queued));
        when(store.claim(eq(queued.getTaskId()), any())).thenReturn(Optional.of(claimed));
        doReturn(heartbeat).when(heartbeatExecutor).scheduleAtFixedRate(
            any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS)
        );
        when(planning.execute(eq(claimed), any())).thenReturn(claimed);

        LocalTaskDispatcher dispatcher = new LocalTaskDispatcher(
            store,
            planning,
            modification,
            knowledge,
            classifier,
            publisher,
            executor,
            heartbeatExecutor,
            4,
            1,
            true
        );

        dispatcher.dispatchDueTasks();
        ArgumentCaptor<Runnable> work = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(work.capture());
        work.getValue().run();

        verify(planning).execute(eq(claimed), any());
        verify(heartbeat).cancel(false);
    }

    @Test
    void schedulesDatabaseRetryForTransientFailure() {
        TripTaskStore store = mock(TripTaskStore.class);
        TripPlanningWorkflow planning = mock(TripPlanningWorkflow.class);
        AiModificationWorkflow modification = mock(AiModificationWorkflow.class);
        KnowledgeIngestionWorkflow knowledge = mock(KnowledgeIngestionWorkflow.class);
        TaskFailureClassifier classifier = mock(TaskFailureClassifier.class);
        TaskRealtimePublisher publisher = mock(TaskRealtimePublisher.class);
        ExecutorService executor = mock(ExecutorService.class);
        ScheduledExecutorService heartbeatExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);

        TripTask queued = task("task-local-retry", TaskType.TRIP_PLAN);
        TripTask claimed = task("task-local-retry", TaskType.TRIP_PLAN);
        claimed.setAttempt(1);
        claimed.setMaxAttempts(4);
        TripTask retrying = task("task-local-retry", TaskType.TRIP_PLAN);
        retrying.setStatus("retrying");
        when(store.dueTasks(anyInt())).thenReturn(List.of(queued));
        when(store.claim(eq(queued.getTaskId()), any())).thenReturn(Optional.of(claimed));
        doReturn(heartbeat).when(heartbeatExecutor).scheduleAtFixedRate(
            any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS)
        );
        RuntimeException failure = new RuntimeException("temporary connection timeout");
        when(planning.execute(eq(claimed), any())).thenThrow(failure);
        when(classifier.classify(failure))
            .thenReturn(new TaskFailureClassifier.Classification(true, "TRANSIENT_EXTERNAL"));
        when(store.scheduleRetry(
            eq(claimed.getTaskId()),
            any(),
            eq("TRANSIENT_EXTERNAL"),
            eq("temporary connection timeout"),
            eq(30)
        )).thenReturn(retrying);

        LocalTaskDispatcher dispatcher = new LocalTaskDispatcher(
            store,
            planning,
            modification,
            knowledge,
            classifier,
            publisher,
            executor,
            heartbeatExecutor,
            4,
            1,
            true
        );

        dispatcher.dispatchDueTasks();
        ArgumentCaptor<Runnable> work = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(work.capture());
        work.getValue().run();

        verify(store).scheduleRetry(
            eq(claimed.getTaskId()),
            any(),
            eq("TRANSIENT_EXTERNAL"),
            eq("temporary connection timeout"),
            eq(30)
        );
        verify(publisher).publish(retrying);
    }

    private TripTask task(String taskId, String taskType) {
        TripTask task = new TripTask();
        task.setTaskId(taskId);
        task.setTaskType(taskType);
        task.setStatus("queued");
        task.setProgress(0);
        return task;
    }
}

package com.zkry.task;

import com.zkry.domain.entity.TripTask;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Records task-state changes after the dedicated socket channel was removed.
 *
 * <p>The authoritative progress is already persisted by {@link TripTaskStore}; clients retrieve
 * it through the standard task-status endpoint. Keeping this small seam avoids coupling task
 * workers to a transport while retaining a useful operational metric.
 */
@Component
public class TaskRealtimePublisher {

    private final Counter stateChanges;

    public TaskRealtimePublisher(MeterRegistry meterRegistry) {
        this.stateChanges = Counter.builder("tripstar.tasks.state-changes")
            .description("Persisted task state changes observed by workers")
            .register(meterRegistry);
    }

    public void publish(TripTask task) {
        if (task != null) stateChanges.increment();
    }
}

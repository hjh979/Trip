package com.zkry.integration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per-provider concurrency guard for paid external calls. */
@Component
public class ExternalCallBulkheads {
    public enum Provider { AI, EMBEDDING, AMAP, MILVUS }

    private final Map<Provider, Semaphore> semaphores = new EnumMap<>(Provider.class);
    private final Map<Provider, Counter> rejected = new EnumMap<>(Provider.class);
    private final long acquireTimeoutMillis;

    public ExternalCallBulkheads(
        MeterRegistry meterRegistry,
        @Value("${tripstar.bulkhead.ai.max-concurrency:4}") int ai,
        @Value("${tripstar.bulkhead.embedding.max-concurrency:4}") int embedding,
        @Value("${tripstar.bulkhead.amap.max-concurrency:8}") int amap,
        @Value("${tripstar.bulkhead.milvus.max-concurrency:8}") int milvus,
        @Value("${tripstar.bulkhead.acquire-timeout-ms:30000}") long timeout
    ) {
        acquireTimeoutMillis = Math.max(100, timeout);
        semaphores.put(Provider.AI, new Semaphore(safe(ai), true));
        semaphores.put(Provider.EMBEDDING, new Semaphore(safe(embedding), true));
        semaphores.put(Provider.AMAP, new Semaphore(safe(amap), true));
        semaphores.put(Provider.MILVUS, new Semaphore(safe(milvus), true));
        for (Provider provider : Provider.values()) {
            rejected.put(provider, meterRegistry.counter("tripstar.external.bulkhead.rejected", "provider", provider.name().toLowerCase()));
            meterRegistry.gauge("tripstar.external.bulkhead.available",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("provider", provider.name().toLowerCase())),
                semaphores.get(provider), Semaphore::availablePermits);
        }
    }

    public Permit acquire(Provider provider) {
        Semaphore semaphore = semaphores.get(provider);
        try {
            if (!semaphore.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS)) {
                rejected.get(provider).increment();
                throw new BulkheadFullException(provider + " capacity is temporarily exhausted");
            }
            return semaphore::release;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BulkheadFullException(provider + " capacity wait was interrupted");
        }
    }

    public <T> T executeUnchecked(Provider provider, java.util.function.Supplier<T> supplier) {
        try (Permit ignored = acquire(provider)) { return supplier.get(); }
    }

    private int safe(int value) { return Math.max(1, Math.min(value, 256)); }

    @FunctionalInterface
    public interface Permit extends AutoCloseable { @Override void close(); }

    public static final class BulkheadFullException extends RuntimeException {
        public BulkheadFullException(String message) { super(message); }
    }
}

package com.zkry.service.planning;

import com.zkry.service.rag.KnowledgeRagService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded, revision-aware cache for reusable RAG context. */
@Service
public class RAGContextCache {
    private static final int DEFAULT_MAX_ENTRIES = 500;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private volatile String corpusRevision;
    private final int maxEntries;
    private final Duration ttl;

    public RAGContextCache() {
        this("v1", DEFAULT_MAX_ENTRIES, DEFAULT_TTL);
    }

    public RAGContextCache(String corpusRevision) {
        this(corpusRevision, DEFAULT_MAX_ENTRIES, DEFAULT_TTL);
    }

    @Autowired
    public RAGContextCache(
        @Value("${tripstar.rag.corpus-revision:${tripstar.rag.knowledge-version:v1}}") String corpusRevision,
        @Value("${tripstar.rag.cache.max-entries:500}") int maxEntries,
        @Value("${tripstar.rag.cache.ttl-seconds:600}") long ttlSeconds
    ) {
        this(corpusRevision, maxEntries, Duration.ofSeconds(Math.max(30, ttlSeconds)));
    }

    private RAGContextCache(String corpusRevision, int maxEntries, Duration ttl) {
        this.corpusRevision = normalize(corpusRevision).isBlank() ? "v1" : normalize(corpusRevision);
        this.maxEntries = Math.max(1, maxEntries);
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    }

    public KnowledgeRagService.GroundingContext getOrLoad(
        String city, String intent, Supplier<KnowledgeRagService.GroundingContext> loader
    ) {
        return getOrLoad("public", "public", city, intent, loader);
    }

    public KnowledgeRagService.GroundingContext getOrLoad(
        String tenantId, String userId, String city, String intent,
        Supplier<KnowledgeRagService.GroundingContext> loader
    ) {
        String key = key(tenantId, userId, city, intent);
        Entry existing = cache.get(key);
        if (existing != null && !existing.expired()) return existing.context();
        if (existing != null) cache.remove(key, existing);
        KnowledgeRagService.GroundingContext loaded = loader == null ? null : loader.get();
        if (loaded == null) return null;
        evictExpiredAndOldest();
        cache.put(key, new Entry(loaded, Instant.now().plus(ttl)));
        return loaded;
    }

    public void put(String city, String intent, KnowledgeRagService.GroundingContext context) {
        if (context == null) return;
        evictExpiredAndOldest();
        cache.put(key("public", "public", city, intent), new Entry(context, Instant.now().plus(ttl)));
    }

    public int size() {
        evictExpiredAndOldest();
        return cache.size();
    }

    public void clear() { cache.clear(); }

    /** Called after a successful corpus write so old retrieval evidence cannot leak into plans. */
    public synchronized void bumpCorpusRevision() {
        corpusRevision = corpusRevision + "." + System.currentTimeMillis();
        cache.clear();
    }

    private void evictExpiredAndOldest() {
        Instant now = Instant.now();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        int overflow = cache.size() - maxEntries + 1;
        if (overflow <= 0) return;
        cache.entrySet().stream()
            .sorted((left, right) -> left.getValue().expiresAt().compareTo(right.getValue().expiresAt()))
            .limit(overflow)
            .forEach(entry -> cache.remove(entry.getKey(), entry.getValue()));
    }

    private String key(String tenantId, String userId, String city, String intent) {
        return normalize(tenantId) + "|" + normalize(userId) + "|" + normalize(city) + "|"
            + normalize(intent) + "|" + corpusRevision;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record Entry(KnowledgeRagService.GroundingContext context, Instant expiresAt) {
        private boolean expired() { return expiresAt.isBefore(Instant.now()); }
    }
}

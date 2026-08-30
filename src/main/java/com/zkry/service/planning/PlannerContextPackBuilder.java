package com.zkry.service.planning;

import com.zkry.domain.dto.planning.PlannerContextPack;
import com.zkry.domain.dto.planning.PlannerEvidenceItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds a source-diverse, size-bounded prompt context.
 *
 * <p>The provider response remains in its durable checkpoint or retrieval trace. Only compact
 * evidence enters the model, which prevents retrieved documents from exhausting the context
 * window. Live AMap results are intentionally obtained only after the planner has selected a
 * knowledge-grounded attraction candidate.
 */
@Service
public class PlannerContextPackBuilder {

    static final int MAX_ITEM_CONTENT_CHARS = 600;
    private static final List<String> SOURCE_ORDER = List.of("USER_PREFERENCE", "KNOWLEDGE");

    private final int maxCharacters;

    public PlannerContextPackBuilder(
        @Value("${tripstar.planning.context.max-characters:10000}") int maxCharacters
    ) {
        this.maxCharacters = Math.max(6000, maxCharacters);
    }

    public PlannerContextPack build(
        List<PlannerEvidenceItem> evidence,
        List<String> traceIds,
        List<String> warnings
    ) {
        List<PlannerEvidenceItem> safeEvidence = evidence == null ? List.of() : evidence;
        Map<String, List<PlannerEvidenceItem>> grouped = new LinkedHashMap<>();
        for (PlannerEvidenceItem item : deduplicate(safeEvidence)) {
            grouped.computeIfAbsent(source(item), ignored -> new ArrayList<>()).add(item);
        }
        grouped.values().forEach(items ->
            items.sort(Comparator.comparingDouble(PlannerEvidenceItem::score).reversed()));

        StringBuilder context = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int perSourceBudget = Math.max(1200, maxCharacters / Math.max(1, SOURCE_ORDER.size()));
        Set<String> sources = new LinkedHashSet<>(SOURCE_ORDER);
        sources.addAll(grouped.keySet());

        for (String source : sources) {
            List<PlannerEvidenceItem> items = grouped.getOrDefault(source, List.of());
            if (items.isEmpty() || context.length() >= maxCharacters) {
                continue;
            }
            int sourceStart = context.length();
            context.append("\n【").append(sourceLabel(source)).append("】\n");
            int included = 0;
            for (PlannerEvidenceItem item : items) {
                if (context.length() >= maxCharacters
                    || context.length() - sourceStart >= perSourceBudget) {
                    break;
                }
                String line = evidenceLine(item);
                int remainingTotal = maxCharacters - context.length();
                int remainingSource = perSourceBudget - (context.length() - sourceStart);
                int allowed = Math.min(remainingTotal, remainingSource);
                if (allowed < 80) {
                    break;
                }
                context.append(truncate(line, allowed));
                included++;
            }
            if (included == 0) {
                context.setLength(sourceStart);
            } else {
                counts.put(source, included);
            }
        }

        List<String> safeTraceIds = traceIds == null ? List.of() : traceIds.stream()
            .filter(this::hasText).distinct().limit(20).toList();
        List<String> safeWarnings = warnings == null ? List.of() : warnings.stream()
            .filter(this::hasText).distinct().limit(20).toList();
        if (!safeWarnings.isEmpty() && context.length() < maxCharacters) {
            String warningBlock = "\n【数据降级说明】\n- " + String.join("\n- ", safeWarnings);
            context.append(truncate(warningBlock, maxCharacters - context.length()));
        }
        return new PlannerContextPack(
            context.toString().trim(),
            Map.copyOf(counts),
            List.copyOf(safeTraceIds),
            List.copyOf(safeWarnings),
            context.length(), false,
            safeEvidence.stream().filter(item -> "KNOWLEDGE".equalsIgnoreCase(source(item)))
                .map(PlannerEvidenceItem::title).filter(this::hasText).distinct().limit(20).toList());
    }

    private List<PlannerEvidenceItem> deduplicate(List<PlannerEvidenceItem> evidence) {
        Map<String, PlannerEvidenceItem> unique = new LinkedHashMap<>();
        for (PlannerEvidenceItem item : evidence) {
            if (item == null || !hasText(item.content())) {
                continue;
            }
            String key = source(item) + "|" + normalize(item.city()) + "|"
                + normalize(item.title()) + "|" + normalize(item.evidenceId());
            PlannerEvidenceItem existing = unique.get(key);
            if (existing == null || item.score() > existing.score()) {
                unique.put(key, item);
            }
        }
        return List.copyOf(unique.values());
    }

    private String evidenceLine(PlannerEvidenceItem item) {
        StringBuilder line = new StringBuilder("- [")
            .append(safe(item.evidenceId(), "no-id"))
            .append("] ");
        if (hasText(item.city())) {
            line.append("城市=").append(item.city()).append("；");
        }
        if (hasText(item.title())) {
            line.append("标题=").append(item.title()).append("；");
        }
        line.append("内容=").append(truncate(item.content().trim(), MAX_ITEM_CONTENT_CHARS));
        if (hasText(item.sourceUrl())) {
            line.append("；引用=").append(item.sourceUrl());
        }
        return line.append('\n').toString();
    }

    private String source(PlannerEvidenceItem item) {
        return hasText(item.source()) ? item.source().trim().toUpperCase(Locale.ROOT) : "OTHER";
    }

    private String sourceLabel(String source) {
        return switch (source) {
            case "USER_PREFERENCE" -> "用户历史偏好";
            case "KNOWLEDGE" -> "自建旅行知识库（官方、网页与导入文档）";
            default -> source;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int max) {
        if (value == null || max <= 0) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return max <= 12 ? value.substring(0, max) : value.substring(0, max - 9) + "…(已裁剪)";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }
}

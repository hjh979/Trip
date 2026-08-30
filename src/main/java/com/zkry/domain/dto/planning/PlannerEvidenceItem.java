package com.zkry.domain.dto.planning;

/**
 * Normalized evidence from RAG, user history or a deterministic external API.
 *
 * <p>Raw provider payloads never enter the model context. Every source is reduced to this
 * contract first so the context pack can enforce source and total size budgets.
 */
public record PlannerEvidenceItem(
    String source,
    String evidenceId,
    String city,
    String title,
    String content,
    String sourceUrl,
    double score
) {
}

package com.zkry.domain.vo;

import java.util.List;

public record RagAnswerView(
    String question,
    String answer,
    String retrieval_mode,
    boolean generated_by_ai,
    List<RagCitationView> citations
) {
}

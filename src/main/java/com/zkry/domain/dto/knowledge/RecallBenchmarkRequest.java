package com.zkry.domain.dto.knowledge;

import java.util.List;

public record RecallBenchmarkRequest(
    Integer top_k,
    List<RecallBenchmarkCase> cases
) {
}

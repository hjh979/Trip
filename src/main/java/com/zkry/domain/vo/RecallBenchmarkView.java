package com.zkry.domain.vo;

import java.util.List;

public record RecallBenchmarkView(
    int top_k,
    int total,
    int hits,
    double recall_at_k,
    double city_precision,
    List<RecallBenchmarkCaseView> cases
) {
}

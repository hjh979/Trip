package com.zkry.domain.dto;

import java.util.List;

public record KnowledgeGraphData(
    List<GraphNode> nodes,
    List<GraphEdge> edges,
    List<GraphCategory> categories
) {
}

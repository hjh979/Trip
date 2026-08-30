package com.zkry.domain.vo;

import java.util.List;

/**
 * 行程工作区右侧地点详情。
 *
 * <p>介绍和攻略分开返回：介绍会保存回行程地点，攻略只使用
 * 自建知识库的 Milvus/关键词混合检索证据生成。
 */
public record TripPlaceGuideView(
    Long item_id,
    String place_name,
    String introduction,
    String answer,
    String retrieval_mode,
    boolean generated_by_ai,
    List<RagCitationView> citations
) {
}

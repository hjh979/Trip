package com.zkry.domain.dto.knowledge;

import java.util.List;

/**
 * 批量同步开放旅行知识库。
 *
 * @param cities 要同步的城市；为空时使用系统维护的基准城市集合
 * @param refresh_existing 外部页面内容变化时是否更新已有文档
 */
public record SyncTravelCorpusRequest(
    List<String> cities,
    Boolean refresh_existing
) {
}

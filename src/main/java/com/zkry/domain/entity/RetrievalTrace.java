package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("retrieval_trace")
public class RetrievalTrace extends BaseEntity {

    private String traceId;
    private String queryText;
    private String filterJson;
    private String retrievalMode;
    private String candidatesJson;
    private String finalCitationsJson;
    private Long latencyMs;
    private Boolean adopted;
}

package com.zkry.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.zkry.common.exception.BizException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class TaskFailureClassifierTest {

    private final TaskFailureClassifier classifier = new TaskFailureClassifier();

    @Test
    void classifiesNestedAgentTimeoutAsTransient() {
        Throwable error = new IllegalStateException(
            "标准行程规划失败",
            new IllegalStateException(
                "ReactAgent 调用失败：Agent 总执行超过 180 秒，已中止本轮并进入任务重试",
                new TimeoutException("agent call timed out")
            )
        );

        TaskFailureClassifier.Classification result = classifier.classify(error);

        assertThat(result.transientFailure()).isTrue();
        assertThat(result.code()).isEqualTo("EXTERNAL_IO");
    }

    @Test
    void classifiesChineseTimeoutMessageAcrossCauseChainAsTransient() {
        Throwable error = new IllegalStateException(
            "外层业务错误",
            new IllegalStateException("模型调用超过 180 秒")
        );

        TaskFailureClassifier.Classification result = classifier.classify(error);

        assertThat(result.transientFailure()).isTrue();
        assertThat(result.code()).isEqualTo("TRANSIENT_EXTERNAL");
    }

    @Test
    void classifiesEmptyProviderResponseAsTransient() {
        TaskFailureClassifier.Classification result =
            classifier.classify(new IllegalStateException("ReactAgent 返回空内容"));

        assertThat(result.transientFailure()).isTrue();
        assertThat(result.code()).isEqualTo("TRANSIENT_EXTERNAL");
    }

    @Test
    void doesNotRetryBusinessLimitMessagesThatContainExceeded() {
        TaskFailureClassifier.Classification result =
            classifier.classify(new BizException("城市数量超过套餐限制"));

        assertThat(result.transientFailure()).isFalse();
        assertThat(result.code()).isEqualTo("BUSINESS_ERROR");
    }
}

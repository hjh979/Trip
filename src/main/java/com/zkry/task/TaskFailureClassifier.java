package com.zkry.task;

import com.zkry.common.exception.BizException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
public class TaskFailureClassifier {

    public Classification classify(Throwable error) {
        Throwable current = error;
        StringBuilder messages = new StringBuilder();
        while (current != null) {
            if (current instanceof SocketTimeoutException
                || current instanceof ConnectException
                || current instanceof IOException
                || current instanceof TimeoutException) {
                return new Classification(true, "EXTERNAL_IO");
            }
            if (current instanceof BizException) {
                return new Classification(false, "BUSINESS_ERROR");
            }
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                messages.append(' ').append(current.getMessage());
            }
            current = current.getCause();
        }
        String message = messages.toString().toLowerCase(Locale.ROOT);
        if (message.contains("429") || message.contains("rate limit") || message.contains("too many")
            || message.contains("timeout") || message.contains("timed out") || message.contains("reset")
            || message.contains("超时") || durationExceeded(message)
            || message.contains("返回空内容") || message.contains("empty content")
            || message.contains("temporarily") || message.contains("connection")
            || message.contains("数据库") || message.contains("deadlock")) {
            return new Classification(true, "TRANSIENT_EXTERNAL");
        }
        return new Classification(false, "PERMANENT_ERROR");
    }

    private boolean durationExceeded(String message) {
        return message.contains("超过")
            && (message.contains("秒") || message.contains("分钟") || message.contains("毫秒"));
    }

    public record Classification(boolean transientFailure, String code) {
    }
}

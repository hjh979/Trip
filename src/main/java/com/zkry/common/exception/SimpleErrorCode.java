package com.zkry.common.exception;

public record SimpleErrorCode(int code, String message, int httpStatus) implements ErrorCode {
}

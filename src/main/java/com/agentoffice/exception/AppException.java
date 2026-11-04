package com.agentoffice.exception;

import java.util.Map;

/**
 * Lỗi nghiệp vụ có mã. GlobalExceptionHandler đổi nó thành HTTP status + ApiError{code,message,requestId,details}.
 * Quy ước: ném AppException thay cho IllegalStateException/RuntimeException trần; bắt lỗi khác thì phải truyền cause.
 */
public class AppException extends RuntimeException {
    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public AppException(ErrorCode code, String message) { this(code, message, null, null); }

    public AppException(ErrorCode code, String message, Map<String, Object> details) { this(code, message, details, null); }

    public AppException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() { return code; }
    public Map<String, Object> details() { return details; }
}

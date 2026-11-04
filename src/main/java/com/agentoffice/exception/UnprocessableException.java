package com.agentoffice.exception;

/** Yêu cầu hợp lệ về cú pháp nhưng vi phạm quy tắc/giới hạn nghiệp vụ -> HTTP 422. Ưu tiên dùng AppException với ErrorCode cụ thể. */
public class UnprocessableException extends AppException {
    public UnprocessableException(String message) { super(ErrorCode.UNPROCESSABLE, message); }
}

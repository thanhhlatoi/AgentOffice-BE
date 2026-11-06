package com.agentoffice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Mã lỗi ổn định trả về cho FE và xuất hiện trong log. Thêm mã mới ở ĐÂY (một chỗ), kèm HTTP status.
 * Nhóm đầu là mã chung đang dùng từ trước (giữ nguyên chuỗi để FE không vỡ); nhóm sau là mã theo chức năng,
 * sẽ được dùng dần khi từng feature được chuyển cấu trúc.
 */
public enum ErrorCode {
    // ---- chung (giữ nguyên giá trị cũ)
    VALIDATION(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    UNPROCESSABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    OPTIMISTIC_LOCK(HttpStatus.CONFLICT),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR),

    // ---- task / orchestrator
    TASK_ILLEGAL_TRANSITION(HttpStatus.CONFLICT),
    TASK_DEPTH_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    TASK_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    DELEGATION_LOOP(HttpStatus.UNPROCESSABLE_ENTITY),
    AGENT_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    GOAL_FINISHED(HttpStatus.UNPROCESSABLE_ENTITY),
    PLAN_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),

    // ---- approval
    APPROVAL_NOT_PENDING(HttpStatus.CONFLICT),
    APPROVAL_EXPIRED(HttpStatus.CONFLICT),
    APPROVAL_POLICY_LOCKED(HttpStatus.UNPROCESSABLE_ENTITY),

    // ---- usage / tool (dành cho các prompt sau)
    BUDGET_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    TOOL_UNKNOWN(HttpStatus.NOT_FOUND),
    TOOL_DENIED_SCOPE(HttpStatus.FORBIDDEN),

    // ---- llm
    LLM_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) { this.status = status; }

    public HttpStatus status() { return status; }

    /** Mã chung cho lỗi do chính Spring MVC sinh ra (400/404/405/413/415...). */
    public static ErrorCode forStatus(HttpStatusCode s) {
        return switch (s.value()) {
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> UNPROCESSABLE;
            default -> s.is4xxClientError() ? VALIDATION : INTERNAL;
        };
    }
}

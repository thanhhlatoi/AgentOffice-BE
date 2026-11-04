package com.agentoffice.domain.task;

public record RunResult(Outcome outcome, String output, String error, long tokensUsed) {
    public enum Outcome {
        SUCCEEDED,
        /** Lỗi tạm thời (timeout, 429, mạng): dispatcher thử lại theo backoff. */
        RETRYABLE_FAILURE,
        FAILED,
        /** Runner đã đặt/đang đặt task sang WAITING_APPROVAL (vd ToolGateway trả PENDING_APPROVAL). */
        WAITING_APPROVAL,
        /** Runner thấy cờ cancelRequested và dừng. */
        CANCELLED,
        /** Task đã bị runner đổi sang trạng thái khác (vd ask_agent -> BLOCKED); dispatcher không làm gì thêm. */
        HANDLED
    }

    public static RunResult succeeded(String output, long tokens) { return new RunResult(Outcome.SUCCEEDED, output, null, tokens); }
    public static RunResult retryable(String error) { return new RunResult(Outcome.RETRYABLE_FAILURE, null, error, 0); }
    public static RunResult failed(String error) { return new RunResult(Outcome.FAILED, null, error, 0); }
    public static RunResult waitingApproval() { return new RunResult(Outcome.WAITING_APPROVAL, null, null, 0); }
    public static RunResult cancelled() { return new RunResult(Outcome.CANCELLED, null, null, 0); }
    public static RunResult handled() { return new RunResult(Outcome.HANDLED, null, null, 0); }
}

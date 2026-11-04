package com.agentoffice.domain.task;

/**
 * Bảng quy tắc kéo thả: kéo thẻ là LỆNH, không phải sửa trạng thái. Lớp thuần, chỉ trả lời "làm gì" hoặc "từ chối vì sao".
 * Khi hai quy tắc cùng khớp, thứ tự ưu tiên: review->* (USE_APPROVAL_DIALOG) trước, rồi *->done (NOT_ALLOWED).
 */
public final class KanbanMoveRules {
    private KanbanMoveRules() {}

    public enum Action {
        /** waiting->waiting: đổi kanbanRank, chèn giữa before/after. */
        REORDER,
        /** waiting(QUEUED)->running: priority=HIGH, nextRunAt=now. KHÔNG ép RUNNING. */
        PROMOTE,
        /** done|closed(FAILED)->waiting: tạo task MỚI (retryOfTaskId), không sửa task cũ. */
        RERUN,
        /** *->closed: huỷ hợp tác (cơ chế BE-1). */
        CANCEL,
        REJECT
    }

    public record Decision(Action action, String reasonCode) {
        static Decision of(Action a) { return new Decision(a, null); }
        static Decision reject(String code) { return new Decision(Action.REJECT, code); }
        public boolean rejected() { return action == Action.REJECT; }
    }

    public static final String DEPENDENCIES_PENDING = "DEPENDENCIES_PENDING";
    public static final String NOT_SUPPORTED = "NOT_SUPPORTED";
    public static final String USE_APPROVAL_DIALOG = "USE_APPROVAL_DIALOG";
    public static final String NOT_ALLOWED = "NOT_ALLOWED";

    public static Decision decide(TaskStatus from, KanbanColumn to) {
        KanbanColumn fc = KanbanColumn.of(from);
        if (fc == KanbanColumn.REVIEW) return Decision.reject(USE_APPROVAL_DIALOG);
        if (to == KanbanColumn.DONE) return Decision.reject(NOT_ALLOWED);
        return switch (to) {
            case WAITING -> switch (fc) {
                case WAITING -> Decision.of(Action.REORDER);
                case DONE -> Decision.of(Action.RERUN);
                case CLOSED -> from == TaskStatus.FAILED ? Decision.of(Action.RERUN) : Decision.reject(NOT_ALLOWED);
                default -> Decision.reject(NOT_SUPPORTED);                     // running->waiting
            };
            case RUNNING -> fc == KanbanColumn.WAITING
                    ? (from == TaskStatus.QUEUED ? Decision.of(Action.PROMOTE) : Decision.reject(DEPENDENCIES_PENDING))
                    : Decision.reject(NOT_SUPPORTED);
            case CLOSED -> switch (fc) {
                case WAITING, RUNNING -> Decision.of(Action.CANCEL);
                case DONE -> Decision.reject(NOT_ALLOWED);                          // huỷ một việc đã xong là vô nghĩa
                default -> Decision.reject(NOT_SUPPORTED);                          // closed->closed
            };
            default -> Decision.reject(NOT_SUPPORTED);                          // *->review: không ai ra lệnh được cho việc chờ duyệt
        };
    }
}

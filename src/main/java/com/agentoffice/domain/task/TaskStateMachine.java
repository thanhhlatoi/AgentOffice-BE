package com.agentoffice.domain.task;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * (Claude dựng theo yêu cầu của chủ dự án.) Lớp thuần: KHÔNG biết DB/event; chỉ trả lời "được/không được")
 *
 * Bảng chuyển hợp lệ (prompt BE-1 mục 6.1):
 *   PENDING | BLOCKED        -> QUEUED
 *   BLOCKED                  -> FAILED             (phụ thuộc FAILED/CANCELLED; cạnh thêm cho TaskScheduler.unblockDependents)
 *   PENDING                  -> BLOCKED            (có phụ thuộc chưa xong)
 *   QUEUED                   -> RUNNING
 *   RUNNING | QUEUED         -> WAITING_BUDGET     (BudgetExceededException)
 *   WAITING_BUDGET           -> QUEUED | CANCELLED (tăng hạn mức / sang kỳ mới)
 *   RUNNING                  -> SUCCEEDED | FAILED | WAITING_APPROVAL | QUEUED (thử lại)
 *   WAITING_APPROVAL         -> RUNNING | FAILED | CANCELLED
 *   mọi trạng thái CHƯA kết thúc -> CANCELLED
 *   SUCCEEDED | FAILED | CANCELLED -> (không đi đâu nữa)
 *
 * !! LỖ HỔNG TRONG SPEC (tôi đã giả định, bạn quyết) !!
 *   ask_agent (mục 7.4) cần "task hiện tại BLOCKED" khi nó đang RUNNING, nhưng bảng trên không có
 *   RUNNING -> BLOCKED. Test của tôi (xem TaskStateMachineTest) giả định BẠN THÊM RUNNING -> BLOCKED.
 *   Nếu bạn không muốn, báo tôi để đổi ask_agent sang cách khác.
 *
 * Gợi ý: EnumMap<TaskStatus, Set<TaskStatus>> dựng một lần (static), tra O(1); trả Set.copyOf/unmodifiable.
 * "Thử lại" (FAILED -> QUEUED) KHÔNG phải cạnh của máy này; thử lại là RUNNING -> QUEUED do TaskScheduler quyết.
 */
public class TaskStateMachine {
    private static final Map<TaskStatus, Set<TaskStatus>> EDGES = build();

    private static Map<TaskStatus, Set<TaskStatus>> build() {
        Map<TaskStatus, Set<TaskStatus>> m = new EnumMap<>(TaskStatus.class);
        m.put(TaskStatus.PENDING, EnumSet.of(TaskStatus.BLOCKED, TaskStatus.QUEUED, TaskStatus.CANCELLED));
        // BLOCKED -> FAILED: phụ thuộc FAILED/CANCELLED thì dependent FAILED ngay, không cần đi vòng qua QUEUED/RUNNING
        m.put(TaskStatus.BLOCKED, EnumSet.of(TaskStatus.QUEUED, TaskStatus.FAILED, TaskStatus.CANCELLED));
        m.put(TaskStatus.QUEUED, EnumSet.of(TaskStatus.RUNNING, TaskStatus.WAITING_BUDGET, TaskStatus.CANCELLED));
        m.put(TaskStatus.RUNNING, EnumSet.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.WAITING_APPROVAL,
                TaskStatus.QUEUED, TaskStatus.BLOCKED, TaskStatus.WAITING_BUDGET, TaskStatus.CANCELLED));
        m.put(TaskStatus.WAITING_APPROVAL, EnumSet.of(TaskStatus.RUNNING, TaskStatus.FAILED, TaskStatus.CANCELLED));
        m.put(TaskStatus.WAITING_BUDGET, EnumSet.of(TaskStatus.QUEUED, TaskStatus.CANCELLED));
        for (TaskStatus t : new TaskStatus[] {TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED})
            m.put(t, EnumSet.noneOf(TaskStatus.class));
        return m;
    }

    public boolean canTransition(TaskStatus from, TaskStatus to) {
        return EDGES.get(from).contains(to);
    }

    /** Ném IllegalStateException nếu không hợp lệ (GlobalExceptionHandler map thành HTTP 409). */
    public void require(TaskStatus from, TaskStatus to) {
        if (!canTransition(from, to)) throw new IllegalStateException("Không thể chuyển task từ " + from + " sang " + to);
    }

    public Set<TaskStatus> allowedFrom(TaskStatus from) {
        return Collections.unmodifiableSet(EDGES.get(from));
    }
}

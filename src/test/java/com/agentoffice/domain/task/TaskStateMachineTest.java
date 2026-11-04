package com.agentoffice.domain.task;

import static com.agentoffice.domain.task.TaskStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Spec cho TaskStateMachine.
 * Bảng chuyển dưới đây là nguồn sự thật: ngoài các cạnh liệt kê, mọi cặp khác phải là false.
 */
@Tag("core")
class TaskStateMachineTest {
    private final TaskStateMachine sm = new TaskStateMachine();

    /** Mọi cạnh hợp lệ. RUNNING -> BLOCKED là GIẢ ĐỊNH của tôi cho ask_agent (spec không liệt kê, xem Javadoc TaskStateMachine). */
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.of(
            PENDING, Set.of(BLOCKED, QUEUED, CANCELLED),
            BLOCKED, Set.of(QUEUED, FAILED, CANCELLED),
            QUEUED, Set.of(RUNNING, WAITING_BUDGET, CANCELLED),
            RUNNING, Set.of(SUCCEEDED, FAILED, WAITING_APPROVAL, QUEUED, BLOCKED, WAITING_BUDGET, CANCELLED),
            WAITING_APPROVAL, Set.of(RUNNING, FAILED, CANCELLED),
            WAITING_BUDGET, Set.of(QUEUED, CANCELLED),
            SUCCEEDED, Set.of(), FAILED, Set.of(), CANCELLED, Set.of());

    @Test void happyPath() {
        assertThat(sm.canTransition(PENDING, QUEUED)).isTrue();
        assertThat(sm.canTransition(QUEUED, RUNNING)).isTrue();
        assertThat(sm.canTransition(RUNNING, SUCCEEDED)).isTrue();
    }

    @Test void blockedUntilDependenciesDone() {
        assertThat(sm.canTransition(PENDING, BLOCKED)).isTrue();
        assertThat(sm.canTransition(BLOCKED, QUEUED)).isTrue();
        assertThat(sm.canTransition(BLOCKED, RUNNING)).isFalse();
    }

    @Test void approvalFlow() {
        assertThat(sm.canTransition(RUNNING, WAITING_APPROVAL)).isTrue();
        assertThat(sm.canTransition(WAITING_APPROVAL, RUNNING)).isTrue();
        assertThat(sm.canTransition(WAITING_APPROVAL, FAILED)).isTrue();
        assertThat(sm.canTransition(WAITING_APPROVAL, SUCCEEDED)).isFalse();
    }

    @Test void retryGoesBackToQueued() {
        assertThat(sm.canTransition(RUNNING, QUEUED)).isTrue();
        assertThat(sm.canTransition(FAILED, QUEUED)).isFalse();      // thử lại thủ công = tạo task mới, không phải cạnh này
    }

    @Test void askAgentBlocksARunningTask() {
        assertThat(sm.canTransition(RUNNING, BLOCKED)).isTrue();     // GIẢ ĐỊNH, xem ghi chú
    }

    @Test void terminalStatesGoNowhere() {
        for (TaskStatus t : new TaskStatus[] {SUCCEEDED, FAILED, CANCELLED})
            for (TaskStatus to : TaskStatus.values())
                assertThat(sm.canTransition(t, to)).as("%s -> %s", t, to).isFalse();
    }

    @Test void anyActiveStateCanBeCancelled() {
        for (TaskStatus s : new TaskStatus[] {PENDING, BLOCKED, QUEUED, RUNNING, WAITING_APPROVAL, WAITING_BUDGET})
            assertThat(sm.canTransition(s, CANCELLED)).as(s.name()).isTrue();
    }

    @Test void budgetWaitKeepsTheTaskAndResumesToQueued() {
        assertThat(sm.canTransition(RUNNING, WAITING_BUDGET)).isTrue();
        assertThat(sm.canTransition(QUEUED, WAITING_BUDGET)).isTrue();
        assertThat(sm.canTransition(WAITING_BUDGET, QUEUED)).isTrue();
        assertThat(sm.canTransition(WAITING_BUDGET, RUNNING)).as("phải qua QUEUED để scheduler chọn lại").isFalse();
        assertThat(sm.canTransition(WAITING_BUDGET, FAILED)).as("hết ngân sách không phải lỗi").isFalse();
    }

    @Test void noSelfTransitions() {
        for (TaskStatus s : TaskStatus.values()) assertThat(sm.canTransition(s, s)).as(s.name()).isFalse();
    }

    @Test void entireTableMatches() {
        for (TaskStatus from : TaskStatus.values())
            for (TaskStatus to : TaskStatus.values())
                assertThat(sm.canTransition(from, to)).as("%s -> %s", from, to).isEqualTo(ALLOWED.get(from).contains(to));
    }

    @Test void allowedFromListsExactlyTheEdges() {
        for (TaskStatus from : TaskStatus.values()) assertThat(sm.allowedFrom(from)).isEqualTo(ALLOWED.get(from));
    }

    @Test void allowedFromIsNotMutable() {
        Set<TaskStatus> s = sm.allowedFrom(QUEUED);
        assertThatThrownBy(() -> s.add(SUCCEEDED)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void requireThrowsOnIllegalAndNamesBothStates() {
        assertThatThrownBy(() -> sm.require(SUCCEEDED, RUNNING))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SUCCEEDED").hasMessageContaining("RUNNING");
    }

    @Test void requirePassesOnLegal() {
        sm.require(QUEUED, RUNNING);
    }
}

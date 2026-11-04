package com.agentoffice.domain.task;

import static com.agentoffice.domain.task.KanbanMoveRules.Action.*;
import static com.agentoffice.domain.task.TaskStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.domain.task.KanbanMoveRules.Action;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MỌI ô của bảng quy tắc kéo thả (9 trạng thái x 5 cột), kể cả ca bị từ chối. */
class KanbanMoveRulesTest {
    private static final String DEP = KanbanMoveRules.DEPENDENCIES_PENDING, NS = KanbanMoveRules.NOT_SUPPORTED,
            DLG = KanbanMoveRules.USE_APPROVAL_DIALOG, NA = KanbanMoveRules.NOT_ALLOWED;

    /** Mỗi ô: Action, hoặc REJECT:<reasonCode>. Thứ tự cột: waiting, running, review, done, closed. */
    private static final Map<TaskStatus, String[]> TABLE = Map.of(
            PENDING,          new String[] {"REORDER", "REJECT:" + DEP, "REJECT:" + NS, "REJECT:" + NA, "CANCEL"},
            BLOCKED,          new String[] {"REORDER", "REJECT:" + DEP, "REJECT:" + NS, "REJECT:" + NA, "CANCEL"},
            QUEUED,           new String[] {"REORDER", "PROMOTE",       "REJECT:" + NS, "REJECT:" + NA, "CANCEL"},
            RUNNING,          new String[] {"REJECT:" + NS, "REJECT:" + NS, "REJECT:" + NS, "REJECT:" + NA, "CANCEL"},
            WAITING_APPROVAL, new String[] {"REJECT:" + DLG, "REJECT:" + DLG, "REJECT:" + DLG, "REJECT:" + DLG, "REJECT:" + DLG},
            WAITING_BUDGET,   new String[] {"REJECT:" + DLG, "REJECT:" + DLG, "REJECT:" + DLG, "REJECT:" + DLG, "REJECT:" + DLG},
            SUCCEEDED,        new String[] {"RERUN", "REJECT:" + NS, "REJECT:" + NS, "REJECT:" + NA, "REJECT:" + NA},
            FAILED,           new String[] {"RERUN", "REJECT:" + NS, "REJECT:" + NS, "REJECT:" + NA, "REJECT:" + NS},
            CANCELLED,        new String[] {"REJECT:" + NA, "REJECT:" + NS, "REJECT:" + NS, "REJECT:" + NA, "REJECT:" + NS});

    @Test void everyCellOfTheTable() {
        KanbanColumn[] cols = {KanbanColumn.WAITING, KanbanColumn.RUNNING, KanbanColumn.REVIEW, KanbanColumn.DONE, KanbanColumn.CLOSED};
        for (TaskStatus from : TaskStatus.values()) {
            for (int i = 0; i < cols.length; i++) {
                var d = KanbanMoveRules.decide(from, cols[i]);
                String actual = d.rejected() ? "REJECT:" + d.reasonCode() : d.action().name();
                assertThat(actual).as("%s -> %s", from, cols[i]).isEqualTo(TABLE.get(from)[i]);
            }
        }
    }

    @Test void theRowsOfTheSpecInPlainWords() {
        assertThat(KanbanMoveRules.decide(QUEUED, KanbanColumn.WAITING).action()).isEqualTo(REORDER);
        assertThat(KanbanMoveRules.decide(QUEUED, KanbanColumn.RUNNING).action()).isEqualTo(PROMOTE);
        assertThat(KanbanMoveRules.decide(BLOCKED, KanbanColumn.RUNNING).reasonCode()).isEqualTo(DEP);
        assertThat(KanbanMoveRules.decide(TaskStatus.RUNNING, KanbanColumn.WAITING).reasonCode()).isEqualTo(NS);
        assertThat(KanbanMoveRules.decide(WAITING_APPROVAL, KanbanColumn.WAITING).reasonCode()).isEqualTo(DLG);
        assertThat(KanbanMoveRules.decide(QUEUED, KanbanColumn.DONE).reasonCode()).as("thả vào Xong không làm task xong").isEqualTo(NA);
        assertThat(KanbanMoveRules.decide(SUCCEEDED, KanbanColumn.WAITING).action()).isEqualTo(Action.RERUN);
        assertThat(KanbanMoveRules.decide(FAILED, KanbanColumn.WAITING).action()).isEqualTo(Action.RERUN);
        assertThat(KanbanMoveRules.decide(TaskStatus.RUNNING, KanbanColumn.CLOSED).action()).isEqualTo(Action.CANCEL);
    }

    @Test void columnsPartitionEveryStatus() {
        assertThat(KanbanColumn.of(PENDING)).isEqualTo(KanbanColumn.WAITING);
        assertThat(KanbanColumn.of(WAITING_BUDGET)).isEqualTo(KanbanColumn.REVIEW);
        assertThat(KanbanColumn.of(CANCELLED)).isEqualTo(KanbanColumn.CLOSED);
        int total = 0;
        for (KanbanColumn c : KanbanColumn.values()) total += c.statuses().size();
        assertThat(total).isEqualTo(TaskStatus.values().length);
    }
}

package com.agentoffice.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.support.PostgresIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class KanbanRankJobIT extends PostgresIT {
    @Autowired KanbanRankJob job;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;

    private Task make(Agent a, String title) {
        return taskService.create(new NewTask(null, null, a.getId(), "HUMAN", title, "in", Priority.NORMAL, 0));
    }

    private List<String> titlesByRank() { return jdbc.queryForList("SELECT title FROM task ORDER BY kanban_rank", String.class); }

    @Test void shortRanksAreLeftAlone() {
        Agent a = agent("a");
        make(a, "x");
        assertThat(job.rebalanceIfNeeded()).isZero();
    }

    @Test void longRanksAreRenumberedKeepingTheOrderAndNewTasksStayLast() {
        Agent a = agent("a");
        Task t1 = make(a, "t1"), t2 = make(a, "t2"), t3 = make(a, "t3");
        String r1 = jdbc.queryForObject("SELECT kanban_rank FROM task WHERE id = ?", String.class, t1.getId());
        jdbc.update("UPDATE task SET kanban_rank = ? WHERE id = ?", r1 + "i".repeat(30), t3.getId());     // t3 chen xuống rất sát sau t1
        List<String> before = titlesByRank();
        assertThat(before).containsExactly("t1", "t3", "t2");

        assertThat(job.rebalanceIfNeeded()).isEqualTo(3);

        assertThat(titlesByRank()).as("thứ tự giữ nguyên").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT max(length(kanban_rank)) FROM task", Integer.class)).isEqualTo(20);
        make(a, "late");
        assertThat(titlesByRank().get(3)).as("task mới sau đợt dồn vẫn ở cuối").isEqualTo("late");
        assertThat(job.rebalanceIfNeeded()).as("đã gọn thì không dồn nữa").isZero();
        assertThat(t2.getId()).isNotNull();
    }
}

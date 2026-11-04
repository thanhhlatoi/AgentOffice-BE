package com.agentoffice.service.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.domain.project.ProjectStatus;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.dto.project.ProjectDto;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.project.Project;
import com.agentoffice.entity.task.Task;
import com.agentoffice.exception.AppException;
import com.agentoffice.repository.project.ProjectRepository;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.service.task.TaskService;
import com.agentoffice.support.PostgresIT;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Tiến độ dự án trên Postgres thật. */
class ProjectServiceIT extends PostgresIT {
    @Autowired ProjectService projects;
    @Autowired ProjectRepository projectRepo;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;

    private Agent agent;

    @BeforeEach void setUp() { agent = agent("a", 20); }

    private Project newProject() { return projects.create(new ProjectDto.Create("P", "d", null, null, "#112233", null)); }

    private UUID goal(UUID projectId) {
        return jdbc.queryForObject("INSERT INTO goal(title, status, project_id) VALUES ('g', 'RUNNING', ?) RETURNING id", UUID.class, projectId);
    }

    private Task task(UUID goalId) {
        Task t = taskService.create(new NewTask(goalId, null, agent.getId(), "HUMAN", "t", "in", Priority.NORMAL, 0));
        return taskService.settle(t.getId());
    }

    private void succeed(Task t) {
        taskService.transition(t.getId(), TaskStatus.RUNNING, "t");
        taskService.complete(t.getId(), "r");
    }

    private void fail(Task t) {
        taskService.transition(t.getId(), TaskStatus.RUNNING, "t");
        taskService.fail(t.getId(), "e");
    }

    private Project reload(Project p) { return projectRepo.findById(p.getId()).orElseThrow(); }

    private List<com.fasterxml.jackson.databind.JsonNode> progressEvents() {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        return events().stream().filter(e -> e.getType().equals("project.progress_changed")).map(e -> {
            try { return om.readTree(e.getPayload()); } catch (Exception x) { throw new RuntimeException(x); }
        }).toList();
    }

    @Test void emptyProjectIsZeroPercentWithoutAPlan() {
        Project p = newProject();
        projects.recompute(p.getId());
        assertThat(reload(p).getProgressPct()).isZero();
        assertThat(reload(p).isHasPlan()).isFalse();
        assertThat(progressEvents()).isEmpty();
        assertThat(eventTypes()).contains("project.created");
    }

    @Test void progressFollowsTheTasksAndEventsFireOnlyOnChange() {
        Project p = newProject();
        UUID g = goal(p.getId());
        Task t1 = task(g), t2 = task(g), t3 = task(g);
        assertThat(reload(p).isHasPlan()).isTrue();
        assertThat(reload(p).getProgressPct()).isZero();
        assertThat(progressEvents()).as("thêm task không đổi % thì không phát").isEmpty();
        succeed(t1);
        assertThat(reload(p).getProgressPct()).isEqualTo(33);
        succeed(t2);
        assertThat(reload(p).getProgressPct()).isEqualTo(66);
        fail(t3);
        assertThat(reload(p).getProgressPct()).as("FAILED vẫn ở mẫu số").isEqualTo(66);
        assertThat(progressEvents()).hasSize(2);
        assertThat(progressEvents().get(0).get("progressPct").asInt()).isEqualTo(33);
        assertThat(progressEvents().get(1).get("progressPct").asInt()).isEqualTo(66);
        assertThat(progressEvents().get(1).get("previousPct").asInt()).isEqualTo(33);
        assertThat(progressEvents().get(1).has("occurredAt")).isTrue();
    }

    @Test void recomputingTwiceChangesNothingAndEmitsNothing() {
        Project p = newProject();
        Task t = task(goal(p.getId()));
        succeed(t);
        int before = progressEvents().size();
        projects.recompute(p.getId());
        projects.recompute(p.getId());
        assertThat(progressEvents()).hasSize(before);
        assertThat(reload(p).getProgressPct()).isEqualTo(100);
    }

    @Test void aFailedTaskPullsTheProgressDownAndTheGoalAndProjectDoNotFinish() {
        Project p = newProject();
        UUID g = goal(p.getId());
        Task t1 = task(g), t2 = task(g);
        succeed(t1);
        fail(t2);
        assertThat(reload(p).getProgressPct()).isEqualTo(50);
        assertThat(reload(p).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test void weightsAndCancelledTasksFollowTheFormula() {
        Project p = newProject();
        UUID g = goal(p.getId());
        Task heavy = task(g), light = task(g), dropped = task(g);
        taskService.updateMeta(heavy.getId(), 3, null);
        taskService.cancel(dropped.getId());
        succeed(heavy);
        assertThat(reload(p).getProgressPct()).as("3 / (3+1), CANCELLED không tính").isEqualTo(75);
        succeed(light);
        assertThat(reload(p).getProgressPct()).isEqualTo(100);
        assertThatThrownBy(() -> taskService.updateMeta(light.getId(), 9, null)).isInstanceOf(RuntimeException.class);
    }

    @Test void projectIsDoneWhenEveryGoalIsDoneAndReopensWhenWorkComesBack() {
        Project p = newProject();
        UUID g1 = goal(p.getId());
        succeed(task(g1));                                              // task cuối xong => goal SUCCEEDED => dự án DONE
        Project done = reload(p);
        assertThat(done.getStatus()).isEqualTo(ProjectStatus.DONE);
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(done.getProgressPct()).isEqualTo(100);

        UUID g2 = goal(null);
        task(g2);
        projects.moveGoal(g2, p.getId());                                // thêm việc mới vào dự án
        Project reopened = reload(p);
        assertThat(reopened.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(reopened.getFinishedAt()).isNull();
        assertThat(reopened.getProgressPct()).isEqualTo(50);
    }

    @Test void pausedProjectsAreNeverAutoClosed() {
        Project p = newProject();
        projects.patch(p.getId(), new ProjectDto.Patch(null, null, ProjectStatus.PAUSED, null, null, null, null));
        succeed(task(goal(p.getId())));
        assertThat(reload(p).getStatus()).isEqualTo(ProjectStatus.PAUSED);
    }

    @Test void movingAGoalMovesItsTasksAndRecomputesBothProjects() {
        Project a = newProject(), b = newProject();
        UUID g = goal(a.getId());
        Task t1 = task(g), t2 = task(g);
        succeed(t1);
        assertThat(reload(a).getProgressPct()).isEqualTo(50);

        projects.moveGoal(g, b.getId());
        assertThat(tasks.findById(t1.getId()).orElseThrow().getProjectId()).isEqualTo(b.getId());
        assertThat(tasks.findById(t2.getId()).orElseThrow().getProjectId()).isEqualTo(b.getId());
        assertThat(reload(a).getProgressPct()).isZero();
        assertThat(reload(a).isHasPlan()).isFalse();
        assertThat(reload(b).getProgressPct()).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT project_id FROM goal WHERE id = ?", UUID.class, g)).isEqualTo(b.getId());

        projects.moveGoal(g, null);                                      // ra khỏi mọi dự án
        assertThat(reload(b).isHasPlan()).isFalse();
        assertThat(tasks.findById(t1.getId()).orElseThrow().getProjectId()).isNull();
    }

    @Test void cannotMoveAGoalIntoAnArchivedProject() {
        Project p = newProject();
        projects.archive(p.getId());
        UUID g = goal(null);
        assertThatThrownBy(() -> projects.moveGoal(g, p.getId())).isInstanceOf(AppException.class);
    }

    @Test void archiveRefusedWhileTasksAreOpen() {
        Project p = newProject();
        Task t = task(goal(p.getId()));
        assertThatThrownBy(() -> projects.archive(p.getId())).isInstanceOf(AppException.class);
        taskService.cancel(t.getId());
        assertThat(projects.archive(p.getId()).getStatus()).isEqualTo(ProjectStatus.ARCHIVED);
    }

    @Test void detailReportsCountsOverdueAndGoalProgress() {
        Project p = projects.create(new ProjectDto.Create("P", null, null, null, null, java.time.Instant.now().minusSeconds(3600)));
        UUID g = goal(p.getId());
        Task t1 = task(g), t2 = task(g);
        succeed(t1);
        fail(t2);
        var d = projects.detail(p.getId());
        assertThat(d.tasksByStatus()).containsEntry("SUCCEEDED", 1L).containsEntry("FAILED", 1L).containsEntry("QUEUED", 0L);
        assertThat(d.failedTasks()).isEqualTo(1);
        assertThat(d.overdue()).isTrue();
        assertThat(d.deadlineRemainingSec()).isNegative();
        assertThat(d.goals()).hasSize(1);
        assertThat(d.goals().get(0).progressPct()).isEqualTo(50);
        assertThat(d.project().progressPct()).isEqualTo(50);
    }

    @Test void dailySnapshotIsOverwrittenNotDuplicated() {
        Project p = newProject();
        UUID g = goal(p.getId());
        Task t1 = task(g), t2 = task(g);
        projects.snapshotToday();
        succeed(t1);
        projects.snapshotToday();
        var h = projects.history(p.getId(), LocalDate.now().minusDays(2), LocalDate.now().plusDays(2));
        assertThat(h).hasSize(1);
        assertThat(h.get(0).progressPct()).isEqualTo(50);
        assertThat(h.get(0).succeeded()).isEqualTo(1);
        assertThat(h.get(0).total()).isEqualTo(2);
        assertThat(t2).isNotNull();
    }

    @Test void twoTasksFinishingAtTheSameTimeStillEndAt100() throws Exception {
        for (int round = 0; round < 5; round++) {
            Project p = newProject();
            UUID g = goal(p.getId());
            Task a = task(g), b = task(g);
            taskService.transition(a.getId(), TaskStatus.RUNNING, "t");
            taskService.transition(b.getId(), TaskStatus.RUNNING, "t");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (Task t : List.of(a, b)) fs.add(pool.submit(() -> { go.await(); return taskService.complete(t.getId(), "r"); }));
            go.countDown();
            for (var f : fs) f.get(20, TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(reload(p).getProgressPct()).as("vòng %d: không được kẹt ở 50", round).isEqualTo(100);
            assertThat(reload(p).getStatus()).isEqualTo(ProjectStatus.DONE);
        }
    }
}

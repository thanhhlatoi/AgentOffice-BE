package com.agentoffice.service.task;

import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.TaskRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.support.CoreIT;
import com.agentoffice.service.system.SystemStateService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec cho TaskScheduler trên Postgres thật. */
class TaskSchedulerIT extends CoreIT {
    @Autowired TaskScheduler scheduler;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired SystemStateService system;

    private Task queued(Agent a, String title, Priority p) {
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", title, "in", p, 0));
        pause(3);                                             // createdAt khác nhau, thứ tự tất định
        return taskService.settle(t.getId());
    }

    private static void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }

    private TaskStatus status(Task t) { return tasks.findById(t.getId()).orElseThrow().getStatus(); }

    private void run(Task t) { taskService.transition(t.getId(), TaskStatus.RUNNING, "test"); }

    @Test void pickClaimsQueuedAsRunning() {
        Agent a = agent("a");
        Task t = queued(a, "t1", Priority.NORMAL);
        List<Task> got = scheduler.pickRunnable(5);
        assertThat(got).extracting(Task::getId).containsExactly(t.getId());
        assertThat(status(t)).isEqualTo(TaskStatus.RUNNING);
        assertThat(tasks.findById(t.getId()).orElseThrow().getStartedAt()).isNotNull();
    }

    @Test void orderIsPriorityThenOldestFirst() {
        Agent a = agent("a", 10);
        Task low = queued(a, "low-old", Priority.LOW);
        Task normalOld = queued(a, "normal-old", Priority.NORMAL);
        Task high = queued(a, "high-new", Priority.HIGH);
        Task normalNew = queued(a, "normal-new", Priority.NORMAL);
        assertThat(scheduler.pickRunnable(10)).extracting(Task::getId)
                .containsExactly(high.getId(), normalOld.getId(), normalNew.getId(), low.getId());
    }

    @Test void limitIsRespected() {
        Agent a = agent("a", 10);
        for (int i = 0; i < 5; i++) queued(a, "t" + i, Priority.NORMAL);
        assertThat(scheduler.pickRunnable(2)).hasSize(2);
    }

    @Test void futureNextRunAtIsSkipped() {
        Agent a = agent("a");
        Task t = queued(a, "later", Priority.NORMAL);
        // đưa về RUNNING rồi hẹn thử lại 1 giờ sau
        run(t);
        taskService.scheduleRetry(t.getId(), Instant.now().plusSeconds(3600), "x");
        assertThat(scheduler.pickRunnable(5)).isEmpty();
        jdbc.update("UPDATE task SET next_run_at = now() - interval '1 second' WHERE id = ?", t.getId());
        assertThat(scheduler.pickRunnable(5)).hasSize(1);
    }

    @Test void oneRunningTaskPerAgentByDefault() {
        Agent a = agent("a", 1);
        queued(a, "t1", Priority.NORMAL);
        queued(a, "t2", Priority.NORMAL);
        assertThat(scheduler.pickRunnable(10)).hasSize(1);
        assertThat(scheduler.pickRunnable(10)).isEmpty();           // t1 còn RUNNING
    }

    @Test void agentConcurrencyLimitIsConfigurablePerAgent() {
        Agent a = agent("a", 2);
        for (int i = 0; i < 4; i++) queued(a, "t" + i, Priority.NORMAL);
        assertThat(scheduler.pickRunnable(10)).hasSize(2);
    }

    @Test void differentAgentsRunInParallel() {
        Agent a = agent("a"), b = agent("b");
        queued(a, "ta", Priority.NORMAL);
        queued(b, "tb", Priority.NORMAL);
        assertThat(scheduler.pickRunnable(10)).hasSize(2);
    }

    @Test void offlineAgentTasksAreSkipped() {
        Agent a = agent("a");
        queued(a, "t", Priority.NORMAL);
        jdbc.update("UPDATE agent SET status = 'OFFLINE' WHERE id = ?", a.getId());
        assertThat(scheduler.pickRunnable(10)).isEmpty();
    }

    @Test void pausedSystemPicksNothing() {
        Agent a = agent("a");
        queued(a, "t", Priority.NORMAL);
        system.pause("test");
        assertThat(scheduler.pickRunnable(10)).isEmpty();
        system.resume("test");
        assertThat(scheduler.pickRunnable(10)).hasSize(1);
    }

    @Test void twoWorkersNeverGetTheSameTask() throws Exception {
        List<Agent> agents = new ArrayList<>();
        for (int i = 0; i < 12; i++) agents.add(agent("a" + i));
        for (Agent a : agents) queued(a, "t-" + a.getName(), Priority.NORMAL);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Task>>> fs = new ArrayList<>();
        for (int w = 0; w < 4; w++) fs.add(pool.submit(() -> { go.await(); return scheduler.pickRunnable(12); }));
        go.countDown();
        List<UUID> all = new ArrayList<>();
        for (var f : fs) f.get(20, TimeUnit.SECONDS).forEach(t -> all.add(t.getId()));
        pool.shutdown();
        assertThat(all).doesNotHaveDuplicates().hasSize(12);
    }

    @Test void backoffGrowsAndCapsAtLastStep() {
        assertThat(scheduler.backoffSeconds(1)).isEqualTo(5);
        assertThat(scheduler.backoffSeconds(2)).isEqualTo(30);
        assertThat(scheduler.backoffSeconds(3)).isEqualTo(120);
        assertThat(scheduler.backoffSeconds(9)).isEqualTo(120);
        assertThatThrownBy(() -> scheduler.backoffSeconds(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- phụ thuộc
    private Task blockedOn(Agent a, String title, Task... deps) {
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", title, "in", Priority.NORMAL, 0));
        for (Task d : deps) taskService.addDependency(t.getId(), d.getId());
        return taskService.settle(t.getId());
    }

    private void succeed(Task t) { run(t); taskService.complete(t.getId(), "ok"); }

    @Test void dependentUnblocksWhenDependencySucceeds() {
        Agent a = agent("a", 5);
        Task first = queued(a, "first", Priority.NORMAL);
        Task second = blockedOn(a, "second", first);
        assertThat(status(second)).isEqualTo(TaskStatus.BLOCKED);
        succeed(first);
        assertThat(status(second)).isEqualTo(TaskStatus.QUEUED);
    }

    @Test void dependentWaitsForAllDependencies() {
        Agent a = agent("a", 5);
        Task d1 = queued(a, "d1", Priority.NORMAL), d2 = queued(a, "d2", Priority.NORMAL);
        Task joined = blockedOn(a, "joined", d1, d2);
        succeed(d1);
        assertThat(status(joined)).isEqualTo(TaskStatus.BLOCKED);
        succeed(d2);
        assertThat(status(joined)).isEqualTo(TaskStatus.QUEUED);
    }

    @Test void twoDependenciesFinishingConcurrentlyStillUnblock() throws Exception {
        Agent a = agent("a", 5);
        Task d1 = queued(a, "d1", Priority.NORMAL), d2 = queued(a, "d2", Priority.NORMAL);
        run(d1); run(d2);
        Task joined = blockedOn(a, "joined", d1, d2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        var f1 = pool.submit(() -> { go.await(); taskService.complete(d1.getId(), "x"); return null; });
        var f2 = pool.submit(() -> { go.await(); taskService.complete(d2.getId(), "y"); return null; });
        go.countDown();
        f1.get(20, TimeUnit.SECONDS); f2.get(20, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(status(joined)).as("không được kẹt BLOCKED mãi (lost wake-up)").isEqualTo(TaskStatus.QUEUED);
    }

    @Test void failedDependencyFailsTheDependentWithAClearReason() {
        Agent a = agent("a", 5);
        Task first = queued(a, "bước-một", Priority.NORMAL);
        Task second = blockedOn(a, "second", first);
        Task third = blockedOn(a, "third", second);
        run(first);
        taskService.fail(first.getId(), "boom");
        Task s = tasks.findById(second.getId()).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(s.getError()).contains("bước-một").contains("FAILED");
        assertThat(status(third)).as("FAILED lan xuống dependents của dependent").isEqualTo(TaskStatus.FAILED);
    }

    @Test void cancelledDependencyAlsoFailsTheDependent() {
        Agent a = agent("a", 5);
        Task first = queued(a, "first", Priority.NORMAL);
        Task second = blockedOn(a, "second", first);
        taskService.cancel(first.getId());
        assertThat(status(second)).isEqualTo(TaskStatus.FAILED);
    }

    // ---- khởi động lại
    @Test void restartMovesRunningBackToQueuedKeepingAttempts() {
        Agent a = agent("a", 5);
        Task t = queued(a, "t", Priority.NORMAL);
        run(t);
        taskService.scheduleRetry(t.getId(), Instant.now(), "e1");           // attempts = 1
        run(t);                                                              // lại RUNNING rồi server chết
        Task waiting = queued(a, "w", Priority.NORMAL);
        run(waiting);
        taskService.transition(waiting.getId(), TaskStatus.WAITING_APPROVAL, "needs approval");

        assertThat(scheduler.recoverAfterRestart()).isEqualTo(1);
        Task back = tasks.findById(t.getId()).orElseThrow();
        assertThat(back.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(back.getAttempts()).isEqualTo(1);
        assertThat(status(waiting)).isEqualTo(TaskStatus.WAITING_APPROVAL);
    }
}

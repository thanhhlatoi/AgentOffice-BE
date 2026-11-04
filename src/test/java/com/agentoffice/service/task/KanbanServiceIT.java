package com.agentoffice.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.domain.task.KanbanColumn;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.dto.task.KanbanDto;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.task.Task;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.support.PostgresIT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Bảng Kanban trên Postgres thật, dùng TaskStateMachine/TaskScheduler thật. */
class KanbanServiceIT extends PostgresIT {
    @Autowired KanbanService kanban;
    @Autowired TaskService taskService;
    @Autowired TaskScheduler scheduler;
    @Autowired TaskRepository tasks;

    private static final KanbanService.Filter ALL = new KanbanService.Filter(null, null, null, null, null, null);

    private static void pause() { try { Thread.sleep(4); } catch (InterruptedException e) { throw new RuntimeException(e); } }

    private Task pending(Agent a, String title, Priority p) {
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", title, "in", p, 0));
        pause();
        return t;
    }

    private Task queued(Agent a, String title) { return queued(a, title, Priority.NORMAL); }

    private Task queued(Agent a, String title, Priority p) { return taskService.settle(pending(a, title, p).getId()); }

    private Task reload(Task t) { return tasks.findById(t.getId()).orElseThrow(); }

    private long v(Task t) { return reload(t).getVersion(); }

    private void run(Task t) { taskService.transition(t.getId(), TaskStatus.RUNNING, "test"); }

    private KanbanDto.Column col(KanbanDto.Board b, String name) { return b.columns().stream().filter(c -> c.column().equals(name)).findFirst().orElseThrow(); }

    private void assertConflict(String reason, Supplier<?> action) {
        assertThatThrownBy(action::get).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(e.details()).containsEntry("reasonCode", reason);
        });
    }

    private KanbanDto.MoveResult move(Task t, KanbanColumn to) { return kanban.move(t.getId(), to, null, null, v(t)); }

    // ================================================================== đọc bảng
    @Test void everyColumnHasATotalALimitedPageAndACursorToLoadMore() {
        Agent a = agent("a", 10);
        List<Task> made = new ArrayList<>();
        for (int i = 0; i < 5; i++) made.add(queued(a, "t" + i));
        var first = col(kanban.board(ALL, 2, null, null), "waiting");
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.cards()).extracting(KanbanDto.Card::id).containsExactly(made.get(0).getId(), made.get(1).getId());
        assertThat(first.nextCursor()).isNotNull();

        var second = col(kanban.board(ALL, 2, KanbanColumn.WAITING, first.nextCursor()), "waiting");
        assertThat(second.cards()).extracting(KanbanDto.Card::id).containsExactly(made.get(2).getId(), made.get(3).getId());
        var third = col(kanban.board(ALL, 2, KanbanColumn.WAITING, second.nextCursor()), "waiting");
        assertThat(third.cards()).extracting(KanbanDto.Card::id).containsExactly(made.get(4).getId());
        assertThat(third.nextCursor()).isNull();

        var board = kanban.board(ALL, 2, null, null);
        assertThat(board.columns()).extracting(KanbanDto.Column::column).containsExactly("waiting", "running", "review", "done", "closed");
        assertThat(col(board, "running").total()).isZero();
    }

    @Test void waitingColumnFollowsTheRealRunOrderPriorityFirst() {
        Agent a = agent("a", 10);
        Task n1 = queued(a, "n1"), low = queued(a, "low", Priority.LOW), high = queued(a, "high", Priority.HIGH), n2 = queued(a, "n2");
        assertThat(col(kanban.board(ALL, 10, null, null), "waiting").cards()).extracting(KanbanDto.Card::id)
                .containsExactly(high.getId(), n1.getId(), n2.getId(), low.getId());
    }

    @Test void columnsGroupStatusesAndReviewShowsBothWaitKinds() {
        Agent a = agent("a", 10);
        Task pend = pending(a, "pend", Priority.NORMAL), runn = queued(a, "runn"), appr = queued(a, "appr"), bud = queued(a, "bud");
        Task ok = queued(a, "ok"), bad = queued(a, "bad"), gone = queued(a, "gone");
        run(runn);
        run(appr); taskService.transition(appr.getId(), TaskStatus.WAITING_APPROVAL, "x");
        run(bud); taskService.waitForBudget(bud.getId(), null, 5);
        run(ok); taskService.complete(ok.getId(), "r");
        run(bad); taskService.fail(bad.getId(), "e");
        taskService.cancel(gone.getId());
        var b = kanban.board(ALL, 10, null, null);
        assertThat(col(b, "waiting").cards()).extracting(KanbanDto.Card::id).containsExactly(pend.getId());
        assertThat(col(b, "running").total()).isEqualTo(1);
        assertThat(col(b, "review").cards()).extracting(KanbanDto.Card::status).containsExactlyInAnyOrder(TaskStatus.WAITING_APPROVAL, TaskStatus.WAITING_BUDGET);
        assertThat(col(b, "review").cards()).allSatisfy(c -> assertThat(c.lockedReason()).isEqualTo("USE_APPROVAL_DIALOG"));
        assertThat(col(b, "done").cards()).extracting(KanbanDto.Card::id).containsExactly(ok.getId());
        assertThat(col(b, "closed").cards()).extracting(KanbanDto.Card::status).containsExactlyInAnyOrder(TaskStatus.FAILED, TaskStatus.CANCELLED);
    }

    @Test void doneColumnShowsTheMostRecentFirst() {
        Agent a = agent("a", 10);
        Task t1 = queued(a, "t1"), t2 = queued(a, "t2");
        run(t1); taskService.complete(t1.getId(), "r");
        pause(); pause();
        run(t2); taskService.complete(t2.getId(), "r");
        var done = col(kanban.board(ALL, 1, null, null), "done");
        assertThat(done.cards()).extracting(KanbanDto.Card::id).containsExactly(t2.getId());
        var more = col(kanban.board(ALL, 1, KanbanColumn.DONE, done.nextCursor()), "done");
        assertThat(more.cards()).extracting(KanbanDto.Card::id).containsExactly(t1.getId());
    }

    @Test void filtersByAgentPriorityAndAccentInsensitiveSearch() {
        Agent a = agent("a", 10), b = agent("b", 10);
        Task rep = queued(a, "Báo cáo doanh thu Đà Nẵng", Priority.HIGH);
        Task other = queued(b, "Dọn kho");
        queued(b, "100% xong_rồi");
        assertThat(ids(kanban.board(new KanbanService.Filter(null, null, a.getId(), null, null, null), 10, null, null))).containsExactly(rep.getId());
        assertThat(ids(kanban.board(new KanbanService.Filter(null, null, null, null, Priority.HIGH, null), 10, null, null))).containsExactly(rep.getId());
        assertThat(ids(kanban.board(new KanbanService.Filter(null, null, null, null, null, "DOANH THU Đà Nẵng"), 10, null, null))).containsExactly(rep.getId());
        assertThat(ids(kanban.board(new KanbanService.Filter(null, null, null, null, null, "DON KHO"), 10, null, null))).containsExactly(other.getId());
        assertThat(ids(kanban.board(new KanbanService.Filter(null, null, null, null, null, "%"), 10, null, null))).as("% là ký tự thường, không phải ký tự đại diện").hasSize(1);
        assertThat(ids(kanban.board(new KanbanService.Filter(null, null, null, a.getDepartmentId(), null, null), 10, null, null))).as("cùng phòng ban 'Test'").hasSize(3);
    }

    private List<UUID> ids(KanbanDto.Board b) { return col(b, "waiting").cards().stream().map(KanbanDto.Card::id).toList(); }

    @Test void cardCarriesAssigneeCostAgeAndTheProgressOfItsSubtasks() {
        clock.set(Instant.parse("2026-03-10T03:00:00Z"));
        Agent a = agent("Linh", 10);
        Task parent = queued(a, "parent");
        Task c1 = taskService.settle(taskService.create(new NewTask(null, parent.getId(), a.getId(), "HUMAN", "c1", "in", Priority.NORMAL, 1)).getId());
        taskService.settle(taskService.create(new NewTask(null, parent.getId(), a.getId(), "HUMAN", "c2", "in", Priority.NORMAL, 1)).getId());
        run(c1); taskService.complete(c1.getId(), "r");
        jdbc.update("INSERT INTO usage_record(agent_id, task_id, model, prompt_tokens, completion_tokens, cost_micro_usd) VALUES (?,?,?,?,?,?)",
                a.getId(), parent.getId(), "m", 1, 1, 1234);
        clock.set(Instant.parse("2026-03-10T03:00:30Z"));
        KanbanDto.Card card = col(kanban.board(ALL, 10, null, null), "waiting").cards().stream().filter(c -> c.id().equals(parent.getId())).findFirst().orElseThrow();
        assertThat(card.assigneeName()).isEqualTo("Linh");
        assertThat(card.costMicroUsd()).isEqualTo(1234);
        assertThat(card.progressPct()).as("1/2 task con xong").isEqualTo(50);
        assertThat(card.version()).isEqualTo(v(parent));
        assertThat(card.ageSec()).isNotNegative();
    }

    // ================================================================== kéo thả
    @Test void reorderingChangesTheSchedulersRunOrder() {
        Agent a = agent("a", 10);
        Task t1 = queued(a, "t1"), t2 = queued(a, "t2"), t3 = queued(a, "t3");
        kanban.move(t3.getId(), KanbanColumn.WAITING, t1.getId(), null, v(t3));         // t3 lên trước t1
        kanban.move(t1.getId(), KanbanColumn.WAITING, null, t2.getId(), v(t1));         // t1 xuống ngay sau t2
        assertThat(col(kanban.board(ALL, 10, null, null), "waiting").cards()).extracting(KanbanDto.Card::id)
                .containsExactly(t3.getId(), t2.getId(), t1.getId());
        assertThat(scheduler.pickRunnable(10)).extracting(Task::getId).containsExactly(t3.getId(), t2.getId(), t1.getId());
    }

    @Test void insertingBetweenTwoAdjacentCardsNeedsNoRenumbering() {
        Agent a = agent("a", 10);
        Task t1 = queued(a, "t1"), t2 = queued(a, "t2"), t3 = queued(a, "t3");
        String r1 = reload(t1).getKanbanRank(), r2 = reload(t2).getKanbanRank();
        kanban.move(t3.getId(), KanbanColumn.WAITING, t2.getId(), t1.getId(), v(t3));   // giữa t1 và t2 (hai hạng kề nhau)
        String r3 = reload(t3).getKanbanRank();
        assertThat(r3.compareTo(r1)).isPositive();
        assertThat(r3.compareTo(r2)).isNegative();
        assertThat(reload(t1).getKanbanRank()).isEqualTo(r1);
        assertThat(reload(t2).getKanbanRank()).isEqualTo(r2);
    }

    @Test void priorityStillBeatsRankInTheSchedulerOrder() {
        Agent a = agent("a", 10);
        Task n1 = queued(a, "n1"), high = queued(a, "high", Priority.HIGH);
        kanban.move(high.getId(), KanbanColumn.WAITING, null, n1.getId(), v(high));     // cố đặt SAU n1
        assertThat(scheduler.pickRunnable(10)).extracting(Task::getId).containsExactly(high.getId(), n1.getId());
    }

    @Test void reorderNeedsANeighbourThatExists() {
        Agent a = agent("a", 10);
        Task t1 = queued(a, "t1");
        assertThatThrownBy(() -> kanban.move(t1.getId(), KanbanColumn.WAITING, null, null, v(t1))).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION));
        assertThatThrownBy(() -> kanban.move(t1.getId(), KanbanColumn.WAITING, t1.getId(), null, v(t1))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> kanban.move(t1.getId(), KanbanColumn.WAITING, UUID.randomUUID(), null, v(t1))).isInstanceOf(NotFoundException.class);
    }

    @Test void dragToRunningPromotesTheQueuedTaskButDoesNotForceItToRun() {
        Agent a = agent("a", 10);
        Task t = queued(a, "t");
        jdbc.update("UPDATE task SET next_run_at = now() + interval '1 hour' WHERE id = ?", t.getId());
        var res = move(t, KanbanColumn.RUNNING);
        assertThat(res.action()).isEqualTo("PROMOTE");
        Task r = reload(t);
        assertThat(r.getStatus()).as("không ép RUNNING").isEqualTo(TaskStatus.QUEUED);
        assertThat(r.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(r.getNextRunAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(scheduler.pickRunnable(5)).extracting(Task::getId).containsExactly(t.getId());
    }

    @Test void dragABlockedTaskToRunningIsRefused() {
        Agent a = agent("a", 10);
        Task first = queued(a, "first");
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", "second", "in", Priority.NORMAL, 0));
        taskService.addDependency(t.getId(), first.getId());
        taskService.settle(t.getId());
        assertConflict("DEPENDENCIES_PENDING", () -> move(t, KanbanColumn.RUNNING));
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test void refusedMovesLeaveTheTaskUntouched() {
        Agent a = agent("a", 10);
        Task running = queued(a, "r"); run(running);
        Task review = queued(a, "v"); run(review); taskService.transition(review.getId(), TaskStatus.WAITING_APPROVAL, "x");
        Task queued = queued(a, "q");
        Task cancelled = queued(a, "c"); taskService.cancel(cancelled.getId());
        assertConflict("NOT_SUPPORTED", () -> move(running, KanbanColumn.WAITING));
        assertConflict("USE_APPROVAL_DIALOG", () -> move(review, KanbanColumn.WAITING));
        assertConflict("USE_APPROVAL_DIALOG", () -> move(review, KanbanColumn.CLOSED));
        assertConflict("NOT_ALLOWED", () -> move(queued, KanbanColumn.DONE));
        assertConflict("NOT_ALLOWED", () -> move(cancelled, KanbanColumn.WAITING));
        assertThat(reload(running).getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(reload(review).getStatus()).isEqualTo(TaskStatus.WAITING_APPROVAL);
        assertThat(reload(queued).getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(eventTypes()).doesNotContain("task.moved");
    }

    @Test void dragDoneOrFailedBackToWaitingCreatesANewTaskAndKeepsTheOldOne() {
        Agent a = agent("a", 10);
        Task ok = queued(a, "ok"); run(ok); taskService.complete(ok.getId(), "r");
        Task bad = queued(a, "bad"); run(bad); taskService.fail(bad.getId(), "e");
        for (Task old : List.of(ok, bad)) {
            var res = move(old, KanbanColumn.WAITING);
            assertThat(res.action()).isEqualTo("RERUN");
            assertThat(res.task().id()).isNotEqualTo(old.getId());
            assertThat(res.task().retryOfTaskId()).isEqualTo(old.getId());
            assertThat(res.task().status()).isEqualTo(TaskStatus.QUEUED);
            assertThat(reload(old).getStatus()).as("task cũ giữ nguyên").isIn(TaskStatus.SUCCEEDED, TaskStatus.FAILED);
        }
        assertThat(eventTypes().stream().filter("task.moved"::equals)).hasSize(2);
    }

    @Test void dragToClosedCancelsCooperatively() {
        Agent a = agent("a", 10);
        Task q = queued(a, "q");
        Task r = queued(a, "r"); run(r);
        move(q, KanbanColumn.CLOSED);
        assertThat(reload(q).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        move(r, KanbanColumn.CLOSED);
        Task running = reload(r);
        assertThat(running.getStatus()).as("đang chạy: chỉ đặt cờ, runner tự dừng").isEqualTo(TaskStatus.RUNNING);
        assertThat(running.isCancelRequested()).isTrue();
    }

    @Test void staleVersionIsRefusedForEveryKindOfMove() {
        Agent a = agent("a", 10);
        Task t = queued(a, "t");
        long old = v(t);
        kanban.move(t.getId(), KanbanColumn.RUNNING, null, null, old);                       // PROMOTE tăng version
        assertConflict("STALE_VERSION", () -> kanban.move(t.getId(), KanbanColumn.RUNNING, null, null, old));
        assertConflict("STALE_VERSION", () -> kanban.move(t.getId(), KanbanColumn.CLOSED, null, null, old));
        Task other = queued(a, "other");
        assertConflict("STALE_VERSION", () -> kanban.move(t.getId(), KanbanColumn.WAITING, other.getId(), null, old));
        assertConflict("STALE_VERSION", () -> kanban.assign(t.getId(), a.getId(), old));
    }

    @Test void twoPeopleDraggingTheSameCardExactlyOneWins() throws Exception {
        Agent a = agent("a", 10);
        Task n1 = queued(a, "n1"), n2 = queued(a, "n2");
        for (int round = 0; round < 6; round++) {
            Task t = queued(a, "t" + round);
            long version = v(t);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> fs = new ArrayList<>();
            UUID[] neighbours = {n1.getId(), n2.getId()};
            for (UUID nb : neighbours) fs.add(pool.submit(() -> {
                go.await();
                try { kanban.move(t.getId(), KanbanColumn.WAITING, nb, null, version); return "OK"; }
                catch (AppException e) { return String.valueOf(e.details().get("reasonCode")); }
            }));
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (var f : fs) outcomes.add(f.get(20, TimeUnit.SECONDS));
            pool.shutdown();
            assertThat(outcomes).as("vòng %d", round).containsExactlyInAnyOrder("OK", "STALE_VERSION");
        }
    }

    // ================================================================== giao việc
    @Test void assignOnlyBeforeTheTaskStartsToAnExistingActiveAgent() {
        Agent a = agent("a", 10), b = agent("b", 10), off = agent("off", 10);
        jdbc.update("UPDATE agent SET status = 'OFFLINE' WHERE id = ?", off.getId());
        Task t = queued(a, "t");
        assertThat(kanban.assign(t.getId(), b.getId(), v(t)).getAssigneeAgentId()).isEqualTo(b.getId());
        assertThat(reload(t).getAssigneeAgentId()).isEqualTo(b.getId());
        assertThatThrownBy(() -> kanban.assign(t.getId(), UUID.randomUUID(), v(t))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> kanban.assign(t.getId(), off.getId(), v(t))).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.AGENT_UNAVAILABLE));
        run(t);
        assertConflict("ALREADY_STARTED", () -> kanban.assign(t.getId(), a.getId(), v(t)));
        assertThat(eventTypes().stream().filter("task.moved"::equals)).hasSize(1);
    }
}

package com.agentoffice.service.task;

import com.agentoffice.config.SchedulerProperties;
import com.agentoffice.config.RedisProperties;
import com.agentoffice.domain.task.AgentRunner;
import com.agentoffice.domain.task.RunResult;
import com.agentoffice.domain.task.TaskContext;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.domain.usage.BudgetExceededException;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.util.LogContext;
import com.agentoffice.service.redis.RedisCoordinator;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * "Ống nước" quanh TaskScheduler: vòng tick, executor có giới hạn luồng, chạy AgentRunner,
 * rồi chuyển trạng thái KẾT QUẢ qua TaskService. Không tự quyết chọn task/backoff — gọi vào TaskScheduler.
 */
@Component
public class TaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final TaskScheduler scheduler;
    private final TaskService taskService;
    private final GoalService goalService;
    private final AgentRepository agents;
    private final AgentRunner runner;
    private final SchedulerProperties props;
    private final Clock clock;
    private final ExecutorService pool;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final RedisCoordinator redis;
    private final RedisProperties redisProps;
    private volatile RedisCoordinator.Lease leader;

    public TaskDispatcher(TaskScheduler scheduler, TaskService taskService, GoalService goalService,
                          AgentRepository agents, AgentRunner runner, SchedulerProperties props, Clock clock,
                          RedisCoordinator redis, RedisProperties redisProps) {
        this.scheduler = scheduler;
        this.taskService = taskService;
        this.goalService = goalService;
        this.agents = agents;
        this.runner = runner;
        this.props = props;
        this.clock = clock;
        this.redis = redis;
        this.redisProps = redisProps;
        // tên luồng hiện trong log và thread dump: biết ngay dòng nào do worker chạy task
        this.pool = Executors.newFixedThreadPool(Math.max(1, props.maxConcurrentTasks()), Thread.ofPlatform().name("task-worker-", 1).factory());
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverOnStartup() {
        if (!props.enabled()) return;
        if (!leader()) {
            log.info("Không khôi phục task: instance khác đang giữ Redis dispatcher lease");
            return;
        }
        log.info("Khôi phục {} task RUNNING sau restart", scheduler.recoverAfterRestart());
    }

    @Scheduled(fixedDelayString = "${agentoffice.scheduler.tick-ms:1000}")
    void scheduledTick() {
        if (!props.enabled()) return;
        if (!leader()) return;
        tick();
    }

    /** Lấy tối đa (maxConcurrent - đang chạy) task và đẩy sang executor. Trả số task đã nhận. */
    public int tick() {
        int free = props.maxConcurrentTasks() - inFlight.get();
        if (free <= 0) return 0;
        List<Task> picked = scheduler.pickRunnable(free);
        if (!picked.isEmpty()) log.debug("tick: nhận {} task (còn trống {})", picked.size(), free);
        for (Task t : picked) {
            inFlight.incrementAndGet();
            pool.submit(() -> {
                try { runOne(t.getId()); } catch (RuntimeException e) { log.error("task {} lỗi ngoài dự kiến", t.getId(), e); }
                finally { inFlight.decrementAndGet(); }
            });
        }
        return picked.size();
    }

    public int inFlight() { return inFlight.get(); }

    private synchronized boolean leader() {
        if (!redis.enabled()) return true;
        if (leader != null && redis.renew(leader, redisProps.dispatcherLease())) return true;
        leader = redis.tryLock("task-dispatcher", redisProps.dispatcherLease()).orElse(null);
        return leader != null;
    }

    /** Chạy đồng bộ 1 task đang RUNNING (public để test gọi trực tiếp). */
    public void runOne(UUID taskId) {
        Task t = taskService.get(taskId);
        // worker không kế thừa MDC của request: đặt lại để mọi dòng log của lượt chạy mang taskId/agentId/goalId
        try (LogContext ignored = LogContext.with("requestId", "job-" + taskId.toString().substring(0, 8),
                "taskId", taskId, "agentId", t.getAssigneeAgentId(), "goalId", t.getGoalId())) {
            execute(t);
        }
    }

    private void execute(Task t) {
        UUID taskId = t.getId();
        if (t.getStatus() != TaskStatus.RUNNING) return;
        if (t.isCancelRequested()) { finish(taskId, () -> taskService.transition(taskId, TaskStatus.CANCELLED, "cancel requested")); return; }

        Agent a = agents.findById(t.getAssigneeAgentId()).orElse(null);
        TaskContext ctx = new TaskContext(taskId, t.getAssigneeAgentId(), a == null ? null : a.getModelProfile(), t.getGoalId(),
                t.getTitle(), t.getInput(), t.getAttempts(), t.getDepth(), () -> taskService.get(taskId).isCancelRequested());
        RunResult r;
        try {
            r = runner.run(ctx);
        } catch (BudgetExceededException e) {
            // không FAILED, không mất task: chờ tăng hạn mức hoặc sang kỳ mới (BudgetService.resumeEligible)
            finish(taskId, () -> taskService.waitForBudget(taskId, e.budgetId(), e.neededMicroUsd()));
            return;
        } catch (RuntimeException e) {
            log.error("Runner {} ném lỗi khi chạy task", runner.getClass().getSimpleName(), e);   // stack trace, không kèm input task
            r = RunResult.failed("Runner ném lỗi: " + e.getClass().getSimpleName());
        }
        log.debug("runner {} trả {}{}", runner.getClass().getSimpleName(), r.outcome(), r.error() == null ? "" : " error=" + r.error());
        if (r.tokensUsed() > 0 && t.getGoalId() != null) goalService.recordTokens(t.getGoalId(), r.tokensUsed());

        Task now = taskService.get(taskId);
        if (now.getStatus() != TaskStatus.RUNNING) return;     // runner/ai đó đã đổi (ask_agent, approval, huỷ goal...)
        final RunResult res = r;
        switch (r.outcome()) {
            case SUCCEEDED -> finish(taskId, () -> taskService.complete(taskId, res.output()));
            case CANCELLED -> finish(taskId, () -> taskService.transition(taskId, TaskStatus.CANCELLED, "cancel requested"));
            case WAITING_APPROVAL -> finish(taskId, () -> taskService.transition(taskId, TaskStatus.WAITING_APPROVAL, "tool needs approval"));
            case FAILED -> finish(taskId, () -> taskService.fail(taskId, res.error()));
            case RETRYABLE_FAILURE -> {
                if (now.getAttempts() + 1 < now.getMaxAttempts()) {
                    var next = clock.instant().plusSeconds(scheduler.backoffSeconds(now.getAttempts() + 1));
                    finish(taskId, () -> taskService.scheduleRetry(taskId, next, res.error()));
                } else {
                    finish(taskId, () -> taskService.fail(taskId, "Hết lượt thử lại: " + res.error()));
                }
            }
            case HANDLED -> { }
        }
    }

    /** Chuyển trạng thái có thể đua với huỷ/huỷ goal: xung đột thì ghi log, không làm sập worker. */
    private void finish(UUID taskId, Runnable step) {
        try { step.run(); }
        catch (IllegalStateException | org.springframework.dao.OptimisticLockingFailureException e) {
            log.warn("Bỏ qua chuyển trạng thái task {} do xung đột: {}", taskId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        if (leader != null) redis.unlock(leader);
    }
}

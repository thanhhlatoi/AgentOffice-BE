package com.agentoffice.service.task;

import com.agentoffice.config.SchedulerProperties;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.task.TaskDependency;
import com.agentoffice.repository.task.TaskDependencyRepository;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.service.system.SystemStateService;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logic chọn task + backoff + mở khoá). Tôi chỉ dựng chữ ký, DI và test (đỏ).
 * Plumbing (vòng @Scheduled, executor, chạy runner) nằm ở TaskDispatcher và gọi vào lớp này.
 *
 * LUẬT SẮT: không sửa cột trạng thái của task bằng SQL/JPQL ở đây. Mọi đổi trạng thái đi qua TaskService.transition
 * (test kiến trúc sẽ đỏ nếu bạn gọi Task.setStatus hoặc viết câu UPDATE trên bảng task).
 *
 * ---- pickRunnable(limit): CLAIM tối đa `limit` task và trả về các task ĐÃ chuyển QUEUED -> RUNNING ----
 *  - Hệ thống đang paused (SystemStateService.isPaused) => trả danh sách rỗng.
 *  - Ứng viên: status=QUEUED và (nextRunAt IS NULL hoặc nextRunAt <= now).
 *  - Thứ tự: priority HIGH > NORMAL > LOW, rồi createdAt cũ trước. Chú ý: sắp theo CHỮ 'HIGH'/'NORMAL'/'LOW'
 *    sẽ sai (H < L < N) — dùng CASE hoặc đổi thứ tự enum/ordinal có chủ đích.
 *  - Mỗi agent chạy tối đa Agent.maxConcurrentTasks task RUNNING cùng lúc (đếm cả những task bạn vừa claim
 *    trong cùng lần gọi!).
 *  - Hai worker/hai instance gọi cùng lúc KHÔNG được lấy trùng 1 task:
 *    native query `SELECT ... FOR UPDATE SKIP LOCKED` (thêm vào TaskRepository) trong CÙNG transaction với
 *    transition(RUNNING). Hãy nghĩ: nếu transition ném lỗi giữa chừng thì sao?
 *  - Agent OFFLINE: bỏ qua task của agent đó.
 *
 * ---- backoffSeconds(attempt) ----
 *  attempt là số lần đã thất bại (1-based): 1 -> 5s, 2 -> 30s, 3 -> 120s. Đọc từ SchedulerProperties.backoffSeconds;
 *  attempt vượt độ dài danh sách thì dùng phần tử cuối. attempt <= 0 là lỗi gọi (IllegalArgumentException).
 *  Quy ước attempts: tăng khi lên lịch thử lại (TaskService.scheduleRetry), dispatcher chỉ thử lại khi
 *  attempts + 1 < maxAttempts.
 *
 * ---- unblockDependents(finished): gọi mỗi khi task chạm trạng thái kết thúc (qua TaskFinishedListener) ----
 *  Cho mỗi task phụ thuộc đang BLOCKED:
 *   - finished SUCCEEDED và MỌI phụ thuộc của nó đều SUCCEEDED => transition BLOCKED -> QUEUED.
 *   - finished FAILED hoặc CANCELLED => dependent chuyển FAILED với lý do rõ ("phụ thuộc <title> đã FAILED/CANCELLED")
 *     (BLOCKED -> FAILED chưa có trong bảng chuyển: cân nhắc BLOCKED -> QUEUED -> ... hay thêm cạnh; tự quyết
 *     và cập nhật TaskStateMachineTest cho khớp). FAILED lan tiếp xuống các dependents của nó (listener tự gọi lại).
 *  Hai phụ thuộc kết thúc cùng lúc ở 2 transaction: cẩn thận lost-wakeup (cả hai thấy "còn 1 cái chưa xong").
 *
 * ---- recoverAfterRestart(): gọi lúc khởi động ----
 *  RUNNING còn sót -> QUEUED (attempts giữ nguyên, không tính là lỗi). WAITING_APPROVAL giữ nguyên.
 *  Trả số task đã khôi phục.
 */
/** Tên bean tường minh: mặc định "taskScheduler" trùng bean của Spring Boot (@EnableScheduling) và app không khởi động được. */
@Component("agentTaskScheduler")
public class TaskScheduler {
    private final TaskRepository tasks;
    private final TaskDependencyRepository deps;
    private final TaskService taskService;
    private final AgentRepository agents;
    private final SystemStateService system;
    private final SchedulerProperties props;
    private final Clock clock;

    public TaskScheduler(TaskRepository tasks, TaskDependencyRepository deps, TaskService taskService,
                         AgentRepository agents, SystemStateService system, SchedulerProperties props, Clock clock) {
        this.tasks = tasks;
        this.deps = deps;
        this.taskService = taskService;
        this.agents = agents;
        this.system = system;
        this.props = props;
        this.clock = clock;
    }

    private static final long PICK_LOCK_KEY = 0x4147454E54L;   // "AGENT"

    @Transactional
    public List<Task> pickRunnable(int limit) {
        if (limit <= 0 || system.isPaused()) return List.of();
        tasks.advisoryLock(PICK_LOCK_KEY);   // tuần tự hoá worker: số chỗ trống của agent được tính trên dữ liệu đã commit
        List<Task> picked = tasks.lockRunnable(clock.instant(), limit);
        // cùng transaction với các hàng đang bị khoá: lỗi giữa chừng thì rollback cả lô, không để task nửa RUNNING nửa QUEUED
        for (Task t : picked) taskService.transition(t.getId(), TaskStatus.RUNNING, "picked by scheduler");
        return picked;
    }

    public long backoffSeconds(int attempt) {
        if (attempt <= 0) throw new IllegalArgumentException("attempt phải >= 1: " + attempt);
        List<Long> steps = props.backoffSeconds();
        return steps.get(Math.min(attempt, steps.size()) - 1);
    }

    @Transactional
    public void unblockDependents(Task finished) {
        for (TaskDependency d : deps.findByDependsOnTaskId(finished.getId())) {
            // khoá dependent TRƯỚC khi kiểm: hai phụ thuộc kết thúc cùng lúc sẽ xếp hàng, kẻ sau thấy kết quả của kẻ trước
            Task dep = tasks.findByIdForUpdate(d.getTaskId()).orElse(null);
            if (dep == null || dep.getStatus() != TaskStatus.BLOCKED) continue;
            switch (finished.getStatus()) {
                case SUCCEEDED -> {
                    if (tasks.countUnfinishedDependencies(dep.getId()) == 0)
                        taskService.transition(dep.getId(), TaskStatus.QUEUED, "dependencies done");
                }
                case FAILED, CANCELLED ->
                    taskService.fail(dep.getId(), "phụ thuộc \"" + finished.getTitle() + "\" đã " + finished.getStatus());
                default -> { }
            }
        }
    }

    @Transactional
    public int recoverAfterRestart() {
        List<Task> running = tasks.findByStatus(TaskStatus.RUNNING);
        for (Task t : running) taskService.transition(t.getId(), TaskStatus.QUEUED, "recovered after restart");   // attempts giữ nguyên
        return running.size();
    }
}

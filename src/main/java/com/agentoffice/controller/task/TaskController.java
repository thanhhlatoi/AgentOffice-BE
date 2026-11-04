package com.agentoffice.controller.task;

import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.dto.task.TaskDto;
import com.agentoffice.mapper.task.TaskMapper;
import com.agentoffice.entity.task.Task;
import com.agentoffice.service.task.TaskService;
import com.agentoffice.dto.common.PageDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Chỉ nhận request -> gọi service. KHÔNG có @Transactional ở đây (quy ước). */
@Validated
@RestController
@RequestMapping("/api")
public class TaskController {
    public record CreateTask(@NotBlank @Size(max = 300) String title,
                             @Size(max = 20000) String input,
                             Priority priority) {}
    public record PatchTask(@NotNull Priority priority) {}
    /** weight 1..5 là trọng số trong tiến độ; labels tối đa 10 nhãn. */
    public record PatchMeta(@Min(1) @Max(5) Integer weight, @Size(max = 10) java.util.List<@NotBlank @Size(max = 40) String> labels) {}

    private final TaskService tasks;

    private final TaskMapper mapper;

    public TaskController(TaskService tasks, TaskMapper mapper) {
        this.tasks = tasks;
        this.mapper = mapper;
    }

    @PostMapping("/agents/{agentId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDto create(@PathVariable UUID agentId, @Valid @RequestBody CreateTask r) {
        Task t = tasks.create(new NewTask(null, null, agentId, "HUMAN", r.title(), r.input(), r.priority(), 0));
        return mapper.toDto(tasks.settle(t.getId()));
    }

    @GetMapping("/tasks")
    public PageDto<TaskDto> list(@RequestParam(required = false) TaskStatus status,
                                 @RequestParam(required = false) UUID agentId,
                                 @RequestParam(required = false) UUID goalId,
                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageDto.of(tasks.search(status, agentId, goalId, page, size).map(mapper::toDto));
    }

    @GetMapping("/tasks/{id}")
    public TaskDto get(@PathVariable UUID id) { return mapper.toDto(tasks.get(id)); }

    @PostMapping("/tasks/{id}/cancel")
    public TaskDto cancel(@PathVariable UUID id) { return mapper.toDto(tasks.cancel(id)); }

    @PostMapping("/tasks/{id}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDto retry(@PathVariable UUID id) { return mapper.toDto(tasks.retry(id)); }

    @PatchMapping("/tasks/{id}/meta")
    public TaskDto patchMeta(@PathVariable UUID id, @Valid @RequestBody PatchMeta r) {
        return mapper.toDto(tasks.updateMeta(id, r.weight(), r.labels()));
    }

    @PatchMapping("/tasks/{id}")
    public TaskDto patch(@PathVariable UUID id, @Valid @RequestBody PatchTask r) {
        return mapper.toDto(tasks.changePriority(id, r.priority()));
    }
}

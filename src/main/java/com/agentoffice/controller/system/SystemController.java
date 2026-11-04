package com.agentoffice.controller.system;

import com.agentoffice.service.system.SystemStateService;
import com.agentoffice.security.SensitiveAction;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.domain.task.TaskStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    public record StateDto(boolean paused, long runningTasks, long queuedTasks) {}

    private final SystemStateService system;
    private final TaskRepository tasks;

    public SystemController(SystemStateService system, TaskRepository tasks) {
        this.system = system;
        this.tasks = tasks;
    }

    @GetMapping("/state")
    public StateDto state() {
        return new StateDto(system.isPaused(), tasks.countByStatus(TaskStatus.RUNNING), tasks.countByStatus(TaskStatus.QUEUED));
    }

    @PostMapping("/pause")
    @SensitiveAction("system.pause")
    public StateDto pause() {
        system.pause("owner");
        return state();
    }

    @PostMapping("/resume")
    @SensitiveAction("system.resume")
    public StateDto resume() {
        system.resume("owner");
        return state();
    }
}

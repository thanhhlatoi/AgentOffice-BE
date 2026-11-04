package com.agentoffice.service.project;

import com.agentoffice.domain.task.ProjectTouched;
import com.agentoffice.domain.task.TaskFinished;
import com.agentoffice.repository.task.TaskRepository;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Chạy ĐỒNG BỘ trong transaction đã đổi task, SAU TaskFinishedListener (1) và tổng hợp goal (2), nên thấy trạng thái goal mới nhất. */
@Component
public class ProjectProgressListener {
    private final TaskRepository tasks;
    private final ProjectService projects;

    public ProjectProgressListener(TaskRepository tasks, ProjectService projects) {
        this.tasks = tasks;
        this.projects = projects;
    }

    @EventListener
    @Order(3)
    public void onTaskFinished(TaskFinished e) {
        tasks.findById(e.taskId()).map(t -> t.getProjectId()).ifPresent(projects::recompute);
    }

    @EventListener
    public void onTouched(ProjectTouched e) { projects.recompute(e.projectId()); }
}

package com.agentoffice.controller.project;

import com.agentoffice.domain.project.ProjectStatus;
import com.agentoffice.dto.project.ProjectDto;
import com.agentoffice.mapper.project.ProjectMapper;
import com.agentoffice.service.project.ProjectService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProjectController {
    private final ProjectService projects;

    private final ProjectMapper mapper;

    public ProjectController(ProjectService projects, ProjectMapper mapper) {
        this.projects = projects;
        this.mapper = mapper;
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDto.View create(@Valid @RequestBody ProjectDto.Create r) { return mapper.toView(projects.create(r)); }

    @GetMapping("/projects")
    public List<ProjectDto.View> list(@RequestParam(required = false) List<ProjectStatus> status) {
        return projects.list(status).stream().map(mapper::toView).toList();
    }

    /** Kèm tiến độ, số task theo trạng thái, chi phí/ngân sách, hạn chót, goals. */
    @GetMapping("/projects/{id}")
    public ProjectDto.Detail get(@PathVariable UUID id) { return projects.detail(id); }

    @PatchMapping("/projects/{id}")
    public ProjectDto.View patch(@PathVariable UUID id, @Valid @RequestBody ProjectDto.Patch r) { return mapper.toView(projects.patch(id, r)); }

    @PostMapping("/projects/{id}/archive")
    public ProjectDto.View archive(@PathVariable UUID id) { return mapper.toView(projects.archive(id)); }

    @GetMapping("/projects/{id}/progress-history")
    public List<ProjectDto.HistoryPoint> history(@PathVariable UUID id,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return projects.history(id, from, to);
    }

    /** projectId null = đưa goal ra khỏi dự án. Mọi task của goal đi theo. */
    @PutMapping("/goals/{id}/project")
    public ProjectDto.MoveGoal moveGoal(@PathVariable UUID id, @RequestBody ProjectDto.MoveGoal r) {
        return new ProjectDto.MoveGoal(projects.moveGoal(id, r.projectId()).getProjectId());
    }
}

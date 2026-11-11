package com.agentoffice.controller.fe;

import jakarta.servlet.http.HttpServletResponse;

import com.agentoffice.controller.support.Paging;

import com.agentoffice.dto.fe.OfficeDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.security.SensitiveAction;
import com.agentoffice.service.fe.CommandService;
import com.agentoffice.service.fe.ViewService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa cho FE (D:\FE\AI-Company-Office): trả đúng hình dạng zod của FE. FE đặt VITE_API_BASE_URL = http://host:8082/api/fe.
 * Các endpoint /api/** gốc của BE không đổi. Phần FE cần mà BE chưa có (kanban, project, chi phí...) vẫn dùng mock ở FE.
 */
@RestController
@RequestMapping("/api/fe")
public class OfficeController {
    private final ViewService view;
    private final CommandService cmd;

    private final com.agentoffice.service.department.DepartmentService departments;

    public OfficeController(ViewService view, CommandService cmd, com.agentoffice.service.department.DepartmentService departments) {
        this.view = view;
        this.cmd = cmd;
        this.departments = departments;
    }

    // ------------------------------------------------------------------ departments / agents
    @GetMapping("/departments")
    public List<OfficeDto.Department> departments() { return view.departments(); }

    private OfficeDto.Department department(UUID id) {
        return view.departments().stream().filter(d -> d.id().equals(id.toString())).findFirst()
                .orElseThrow(() -> new com.agentoffice.exception.NotFoundException("department", id));
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeDto.Department createDepartment(@RequestBody OfficeDto.DepartmentInput in) { return department(departments.create(in)); }

    @PutMapping("/departments/{id}")
    public OfficeDto.Department updateDepartment(@PathVariable UUID id, @RequestBody OfficeDto.DepartmentInput in) {
        departments.update(id, in);
        return department(id);
    }

    @DeleteMapping("/departments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDepartment(@PathVariable UUID id) { departments.delete(id); }

    @GetMapping("/agents")
    public List<OfficeDto.Agent> agents(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, HttpServletResponse res) { return Paging.slice(view.agents(), page, size, res); }

    @GetMapping("/agents/{id}")
    public OfficeDto.Agent agent(@PathVariable UUID id) { return view.agent(id); }

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeDto.Agent createAgent(@RequestBody OfficeDto.AgentInput in) {
        if (in.name() == null || in.name().isBlank() || in.role() == null || in.role().isBlank())
            throw new AppException(ErrorCode.VALIDATION, "Tên và vai trò là bắt buộc");
        return view.agent(cmd.createAgent(in));
    }

    @PutMapping("/agents/{id}")
    public OfficeDto.Agent updateAgent(@PathVariable UUID id, @RequestBody OfficeDto.AgentInput in) {
        return view.agent(cmd.updateAgent(id, in));
    }

    /** Lưu trữ agent (ẩn, OFFLINE, xoá bot Hermes); dòng DB được giữ vì lịch sử còn tham chiếu. */
    @DeleteMapping("/agents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAgent(@PathVariable UUID id) { cmd.deleteAgent(id); }

    // ------------------------------------------------------------------ tasks
    @GetMapping("/agents/{id}/tasks")
    public List<OfficeDto.Task> agentTasks(@PathVariable UUID id, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, HttpServletResponse res) { return Paging.slice(view.tasksOfAgent(id), page, size, res); }

    @PostMapping("/agents/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeDto.Task createTask(@PathVariable UUID id, @RequestBody OfficeDto.TaskBody body) {
        return view.task(cmd.createTask(id, body));
    }

    @GetMapping("/tasks")
    public List<OfficeDto.Task> tasks(@RequestParam(required = false) String status, @RequestParam(required = false) UUID agentId,
                                      @RequestParam(required = false) String q, @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                                      @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, HttpServletResponse res) { return Paging.slice(filterTasks(view.tasks(), status, agentId, q, from, to), page, size, res); }

    /** Lọc trước khi phân trang: trạng thái, agent, từ khóa trong tiêu đề, ngày tạo (gồm hai đầu, giờ Việt Nam). */
    private static List<OfficeDto.Task> filterTasks(List<OfficeDto.Task> all, String status, UUID agentId, String q, java.time.LocalDate from, java.time.LocalDate to) {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        String needle = q == null ? "" : q.strip().toLowerCase(java.util.Locale.ROOT);
        return all.stream()
                .filter(t -> status == null || status.isBlank() || status.equalsIgnoreCase(t.status()))
                .filter(t -> agentId == null || agentId.toString().equals(t.agentId()))
                .filter(t -> needle.isEmpty() || (t.title() != null && t.title().toLowerCase(java.util.Locale.ROOT).contains(needle)))
                .filter(t -> from == null || (t.createdAt() != null && !t.createdAt().isBefore(from.atStartOfDay(zone).toInstant())))
                .filter(t -> to == null || (t.createdAt() != null && t.createdAt().isBefore(to.plusDays(1).atStartOfDay(zone).toInstant())))
                .toList();
    }

    @PostMapping("/tasks/{id}/cancel")
    public OfficeDto.Task cancelTask(@PathVariable UUID id) { return view.task(cmd.cancelTask(id)); }

    @PostMapping("/tasks/{id}/retry")
    public OfficeDto.Task retryTask(@PathVariable UUID id) { return view.task(cmd.retryTask(id)); }

    @PutMapping("/tasks/{id}/priority")
    public OfficeDto.Task priority(@PathVariable UUID id, @RequestBody OfficeDto.PriorityBody body) {
        return view.task(cmd.setPriority(id, body.priority()));
    }

    // ------------------------------------------------------------------ goals
    @GetMapping("/goals")
    public List<OfficeDto.Goal> goals(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, HttpServletResponse res) { return Paging.slice(view.goals(), page, size, res); }

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeDto.Goal createGoal(@RequestBody OfficeDto.GoalInput in) { return view.goal(cmd.createGoal(in)); }

    @PostMapping("/goals/{id}/approve-plan")
    @SensitiveAction("goal.plan.approve")
    public OfficeDto.Goal approvePlan(@PathVariable UUID id) {
        cmd.approvePlan(id);
        return view.goal(id);
    }

    @PostMapping("/goals/{id}/reject-plan")
    @SensitiveAction("goal.plan.reject")
    public OfficeDto.Goal rejectPlan(@PathVariable UUID id, @RequestBody OfficeDto.ReasonBody body) {
        cmd.rejectPlan(id, body.reason());
        return view.goal(id);
    }

    @PostMapping("/goals/{id}/cancel")
    @SensitiveAction("goal.cancel")
    public OfficeDto.Goal cancelGoal(@PathVariable UUID id) {
        cmd.cancelGoal(id);
        return view.goal(id);
    }

    // ------------------------------------------------------------------ approvals
    @GetMapping("/approvals")
    public List<OfficeDto.Approval> approvals(@RequestParam(defaultValue = "PENDING") String status, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, HttpServletResponse res) { return Paging.slice(view.approvals(status), page, size, res); }

    @PostMapping("/approvals/{id}/approve")
    @SensitiveAction("approval.approve")
    public OfficeDto.ApproveResult approve(@PathVariable UUID id) {
        cmd.approve(id);
        return new OfficeDto.ApproveResult(id.toString(), view.approval(id).status());
    }

    @PostMapping("/approvals/{id}/reject")
    @SensitiveAction("approval.reject")
    public OfficeDto.ApproveResult reject(@PathVariable UUID id, @RequestBody OfficeDto.ReasonBody body) {
        cmd.reject(id, body.reason());
        return new OfficeDto.ApproveResult(id.toString(), view.approval(id).status());
    }

    // ------------------------------------------------------------------ system / events
    @GetMapping("/system")
    public OfficeDto.SystemState system() { return view.systemState(); }

    @PostMapping("/system/pause")
    @SensitiveAction("system.pause")
    public OfficeDto.SystemState pause(@RequestBody(required = false) OfficeDto.ReasonBody body) {
        cmd.pause();
        return view.systemState();
    }

    @PostMapping("/system/resume")
    @SensitiveAction("system.resume")
    public OfficeDto.SystemState resume() {
        cmd.resume();
        return view.systemState();
    }

    @GetMapping("/events")
    public OfficeDto.EventsPage events(@RequestParam(defaultValue = "0") long afterId, @RequestParam(required = false) Integer limit) {
        return view.eventsAfter(afterId);
    }
}

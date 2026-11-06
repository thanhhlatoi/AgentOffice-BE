package com.agentoffice.controller.fe;

import com.agentoffice.dto.fe.BizDto;
import com.agentoffice.dto.fe.OfficeDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.service.fe.BizService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Kanban, dự án, chi phí cho FE (hợp đồng: BizDto). Cùng cửa /api/fe với OfficeController. */
@RestController
@RequestMapping("/api/fe")
public class BizController {
    private final BizService biz;

    private final com.agentoffice.service.usage.BudgetService budgetService;

    public BizController(BizService biz, com.agentoffice.service.usage.BudgetService budgetService) {
        this.biz = biz;
        this.budgetService = budgetService;
    }

    /** FE đọc `reasonCode` ở CẤP NGOÀI của thân lỗi (khác ApiError gốc để trong `details`), nên làm phẳng cho nhóm này. */
    @ExceptionHandler(AppException.class)
    ResponseEntity<Map<String, Object>> app(AppException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code().name());
        body.put("message", e.getMessage());
        if (e.details() != null) {
            body.putAll(e.details());
            body.put("details", e.details());
        }
        return ResponseEntity.status(e.code().status()).body(body);
    }

    // ---- task / kanban
    @GetMapping("/tasks/{id}")
    public OfficeDto.Task task(@PathVariable UUID id) { return biz.taskById(id); }

    @GetMapping("/tasks/{id}/history")
    public List<BizDto.History> history(@PathVariable UUID id) { return biz.taskHistory(id); }

    @PostMapping("/tasks/{id}/assign")
    public OfficeDto.Task assign(@PathVariable UUID id, @RequestBody BizDto.AssignBody b) { return biz.assign(id, b.agentId()); }

    @PostMapping("/tasks/{id}/move")
    public OfficeDto.Task move(@PathVariable UUID id, @RequestBody BizDto.MoveBody b) { return biz.move(id, b); }

    @GetMapping("/kanban/columns/{column}")
    public BizDto.Page<OfficeDto.Task> column(@PathVariable String column, @RequestParam(required = false) UUID projectId,
                                            @RequestParam(required = false) UUID agentId, @RequestParam(required = false) UUID departmentId,
                                            @RequestParam(required = false) String priority, @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return biz.kanbanPage(column, projectId, agentId, departmentId, priority, q, cursor, Math.max(1, Math.min(limit, 200)));
    }

    // ---- dự án
    @GetMapping("/projects")
    public List<BizDto.Project> projects(@RequestParam(required = false) String status, @RequestParam(required = false) String departmentId,
                                           @RequestParam(required = false) String q) { return biz.projects(status, departmentId, q); }

    @GetMapping("/projects/{id}")
    public BizDto.Project project(@PathVariable UUID id) { return biz.project(id); }

    @GetMapping("/projects/{id}/snapshots")
    public List<BizDto.Snapshot> snapshots(@PathVariable UUID id) { return biz.snapshots(id); }

    @GetMapping("/projects/{id}/events")
    public List<BizDto.Activity> activity(@PathVariable UUID id) { return biz.projectActivity(id); }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public BizDto.Project createProject(@RequestBody BizDto.ProjectInput in) { return biz.createProject(in); }

    @PostMapping("/projects/{id}/archive")
    public BizDto.Project archive(@PathVariable UUID id) { return biz.archiveProject(id); }

    @PostMapping("/projects/{id}/reopen")
    public BizDto.Project reopen(@PathVariable UUID id) { return biz.reopenProject(id); }

    @PostMapping("/goals/{id}/project")
    public OfficeDto.Goal goalProject(@PathVariable UUID id, @RequestBody BizDto.ProjectBody b) { return biz.moveGoal(id, b.projectId()); }

    // ---- chi phí
    @GetMapping("/usage")
    public BizDto.Usage usage(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                @RequestParam(required = false) String groupBy, @RequestParam(required = false) UUID projectId,
                                @RequestParam(required = false) UUID agentId) { return biz.usage(from, to, groupBy, projectId, agentId); }

    @GetMapping("/model-prices")
    public List<BizDto.ModelPrice> prices() { return biz.modelPrices(); }

    @PostMapping("/model-prices")
    @ResponseStatus(HttpStatus.CREATED)
    public BizDto.ModelPrice addPrice(@RequestBody BizDto.ModelPriceInput in) { return biz.addModelPrice(in); }

    @GetMapping("/budgets")
    public List<BizDto.Budget> budgets() { return biz.budgets(); }

    @PostMapping("/budgets")
    @ResponseStatus(HttpStatus.CREATED)
    public BizDto.Budget createBudget(@RequestBody BizDto.BudgetInput in) { return biz.createBudget(in); }

    @PutMapping("/budgets/{id}")
    public BizDto.Budget updateBudget(@PathVariable UUID id, @RequestBody BizDto.BudgetUpdate u) {
        Long raise = biz.validateBudgetUpdate(id, u);
        if (raise != null) budgetService.raise(id, raise, u.reason().trim(), "owner");
        return biz.finishBudgetUpdate(id, u);
    }

    @GetMapping("/budgets/{id}/changes")
    public List<BizDto.BudgetChange> changes(@PathVariable UUID id) { return biz.budgetChanges(id); }

    // ---- số liệu phòng
    @GetMapping("/departments/{id}/stats")
    public BizDto.DeptStats deptStats(@PathVariable UUID id) { return biz.departmentStats(id); }
}

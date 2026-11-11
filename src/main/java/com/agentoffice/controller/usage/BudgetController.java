package com.agentoffice.controller.usage;

import jakarta.servlet.http.HttpServletResponse;

import com.agentoffice.controller.support.Paging;

import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.dto.usage.BudgetDto;
import com.agentoffice.mapper.usage.BudgetMapper;
import com.agentoffice.security.SensitiveAction;
import com.agentoffice.service.usage.BudgetService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Chỉ con người dùng API này. Agent/tool không có đường nào tới đây (ArchitectureTest canh). */
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {
    private static final String OWNER = "owner";   // chưa có xác thực chủ; @SensitiveAction là điểm cắm
    private final BudgetService budgets;

    private final BudgetMapper mapper;

    public BudgetController(BudgetService budgets, BudgetMapper mapper) {
        this.budgets = budgets;
        this.mapper = mapper;
    }

    @GetMapping
    public List<BudgetDto.View> list(@RequestParam(required = false) BudgetScope scopeType, @RequestParam(required = false) UUID scopeId) {
        return mapper.toViews(budgets.list(scopeType, scopeId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SensitiveAction("budget.create")
    public BudgetDto.View create(@Valid @RequestBody BudgetDto.Create r) { return mapper.toView(budgets.create(r, OWNER)); }

    @GetMapping("/{id}")
    public BudgetDto.View get(@PathVariable UUID id) { return mapper.toView(budgets.get(id)); }

    @PatchMapping("/{id}")
    @SensitiveAction("budget.patch")
    public BudgetDto.View patch(@PathVariable UUID id, @Valid @RequestBody BudgetDto.Patch r) { return mapper.toView(budgets.patch(id, r, OWNER)); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SensitiveAction("budget.delete")
    public void delete(@PathVariable UUID id) { budgets.delete(id, OWNER); }

    @GetMapping("/{id}/status")
    public BudgetDto.Status status(@PathVariable UUID id) { return budgets.status(id); }

    /** Chỉ tăng. Ghi lịch sử + audit và cho task đang chờ chạy tiếp. */
    @PostMapping("/{id}/raise")
    @SensitiveAction("budget.raise")
    public BudgetDto.View raise(@PathVariable UUID id, @Valid @RequestBody BudgetDto.Raise r) {
        return mapper.toView(budgets.raise(id, r.newLimit(), r.reason(), OWNER));
    }

    @GetMapping("/{id}/changes")
    public List<BudgetDto.Change> changes(@PathVariable UUID id, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, HttpServletResponse res) { return Paging.slice(mapper.toChanges(budgets.changes(id)), page, size, res); }
}

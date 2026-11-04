package com.agentoffice.controller.task;

import com.agentoffice.domain.task.KanbanColumn;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.dto.task.KanbanDto;
import com.agentoffice.dto.task.TaskDto;
import com.agentoffice.mapper.task.TaskMapper;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.service.task.KanbanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Locale;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api")
public class KanbanController {
    private final KanbanService kanban;

    private final TaskMapper mapper;

    public KanbanController(KanbanService kanban, TaskMapper mapper) {
        this.kanban = kanban;
        this.mapper = mapper;
    }

    /** 5 cột (waiting|running|review|done|closed), mỗi cột: total + tối đa `limit` thẻ + nextCursor. Tải thêm: column=<tên>&cursor=<nextCursor>. */
    @GetMapping("/kanban")
    public KanbanDto.Board board(@RequestParam(required = false) UUID projectId, @RequestParam(required = false) UUID goalId,
                                 @RequestParam(required = false) UUID agentId, @RequestParam(required = false) UUID departmentId,
                                 @RequestParam(required = false) Priority priority, @RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
                                 @RequestParam(required = false) String column, @RequestParam(required = false) String cursor) {
        return kanban.board(new KanbanService.Filter(projectId, goalId, agentId, departmentId, priority, q), limit,
                column == null ? null : parse(column), cursor);
    }

    /** Kéo thả là LỆNH. Chỉ trả 200 hoặc 409 kèm details.reasonCode (STALE_VERSION, NOT_ALLOWED, NOT_SUPPORTED, USE_APPROVAL_DIALOG, DEPENDENCIES_PENDING...). */
    @PostMapping("/tasks/{id}/move")
    public KanbanDto.MoveResult move(@PathVariable UUID id, @Valid @RequestBody KanbanDto.MoveRequest r) {
        return kanban.move(id, parse(r.toColumn()), r.beforeTaskId(), r.afterTaskId(), r.expectedVersion());
    }

    @PostMapping("/tasks/{id}/assign")
    public TaskDto assign(@PathVariable UUID id, @Valid @RequestBody KanbanDto.AssignRequest r) {
        return mapper.toDto(kanban.assign(id, r.agentId(), r.expectedVersion()));
    }

    private static KanbanColumn parse(String s) {
        try { return KanbanColumn.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new AppException(ErrorCode.VALIDATION, "Cột phải là waiting|running|review|done|closed"); }
    }
}

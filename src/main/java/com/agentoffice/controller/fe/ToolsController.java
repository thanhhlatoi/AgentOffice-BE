package com.agentoffice.controller.fe;

import com.agentoffice.dto.fe.BizDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.service.fe.ToolsService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Công cụ, quyền, bí mật, nhật ký cho FE (hợp đồng: BizDto, FE types/tools.ts). Quyền chỉ được LƯU và tính; chưa ép lúc agent gọi tool (xem ToolsService). */
@RestController
@RequestMapping("/api/fe")
public class ToolsController {
    private final ToolsService tools;

    public ToolsController(ToolsService tools) { this.tools = tools; }

    @GetMapping("/tools")
    public List<BizDto.ToolView> tools() { return tools.tools(); }

    @PutMapping("/tools/{id}")
    public BizDto.ToolView patch(@PathVariable UUID id, @RequestBody BizDto.ToolPatch p) { return tools.patchTool(id, p); }

    @PostMapping("/tools/{id}/check")
    public BizDto.CheckResult check(@PathVariable UUID id, @RequestBody BizDto.CheckBody b) { return tools.check(id, b); }

    @GetMapping("/grants")
    public List<BizDto.GrantView> grants(@RequestParam(required = false) UUID agentId) { return tools.grants(agentId); }

    @GetMapping("/agents/{id}/effective-permissions")
    public BizDto.Effective effective(@PathVariable UUID id) { return tools.effective(id); }

    @GetMapping("/effective-permissions")
    public List<BizDto.Effective> effectiveAll() { return tools.effectiveAll(); }

    @PostMapping("/agents/{id}/permissions/preview")
    public BizDto.Effective preview(@PathVariable UUID id, @RequestBody BizDto.PermissionChange c) { return tools.preview(id, c); }

    @PutMapping("/agents/{id}/grants")
    public BizDto.Effective saveGrants(@PathVariable UUID id, @RequestBody BizDto.PermissionChange c) { return tools.saveGrants(id, c); }

    @GetMapping("/tool-calls")
    public BizDto.Page<BizDto.ToolCall> toolCalls(@RequestParam(required = false) UUID agentId, @RequestParam(required = false) UUID toolId,
                                                      @RequestParam(required = false) String decision, @RequestParam(required = false) Boolean denied,
                                                      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") int limit) {
        return tools.toolCalls(agentId, toolId, decision, denied, cursor, Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/permission-audit")
    public BizDto.Page<BizDto.AuditEntry> audit(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") int limit) {
        return tools.permissionAudit(cursor, Math.max(1, Math.min(limit, 100)));
    }

    // ---- bí mật: chỉ xem danh sách và xoá. Ghi/xoay giá trị cần SecretService (phần "bạn tự viết") nên trả 501 thay vì lưu sai cách.
    @GetMapping("/secrets")
    public List<BizDto.SecretView> secrets() { return tools.secrets(); }

    @RequestMapping(path = {"/secrets", "/secrets/{id}/rotate"}, method = RequestMethod.POST)
    public void writeSecret() {
        throw new AppException(ErrorCode.NOT_IMPLEMENTED, "Lưu/xoay bí mật chưa được hỗ trợ: SecretService (mã hoá) còn là phần chưa viết");
    }

    @DeleteMapping("/secrets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSecret(@PathVariable UUID id) { tools.deleteSecret(id); }
}

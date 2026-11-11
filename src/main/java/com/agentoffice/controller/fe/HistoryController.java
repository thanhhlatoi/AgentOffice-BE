package com.agentoffice.controller.fe;

import com.agentoffice.dto.fe.BizDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.service.fe.HistoryService;
import com.agentoffice.service.fe.NotifyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.agentoffice.service.redis.RedisCoordinator;
import java.time.Duration;

/** Thông báo trong app, cài đặt thông báo và lịch sử hội thoại cho FE (hợp đồng: BizDto, FE types/{notify,history}.ts). */
@RestController
@RequestMapping("/api/fe")
public class HistoryController {
    private final NotifyService notify;
    private final HistoryService history;
    private final com.agentoffice.service.fe.StatsService stats;
    private final RedisCoordinator redis;

    public HistoryController(NotifyService notify, HistoryService history, com.agentoffice.service.fe.StatsService stats, RedisCoordinator redis) {
        this.notify = notify;
        this.history = history;
        this.stats = stats;
        this.redis = redis;
    }

    // ---- dashboard
    @GetMapping("/stats/overview")
    public com.agentoffice.service.fe.StatsService.Overview overview(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                                     @RequestParam(required = false) UUID departmentId, @RequestParam(required = false) UUID projectId) {
        String key = "stats-overview:" + value(from) + ":" + value(to) + ":" + value(departmentId) + ":" + value(projectId);
        return redis.cached(key, com.agentoffice.service.fe.StatsService.Overview.class, Duration.ofSeconds(5),
                () -> stats.overview(from, to, departmentId, projectId));
    }

    // ---- thông báo
    @GetMapping("/notifications")
    public BizDto.Page<BizDto.Notification> notifications(@RequestParam(required = false) String level, @RequestParam(required = false) String type,
                                                              @RequestParam(required = false) Boolean unread, @RequestParam(required = false) String cursor,
                                                              @RequestParam(defaultValue = "30") int limit) {
        return notify.list(level, type, unread, cursor, clamp(limit, 100));
    }

    @GetMapping("/notifications/unread-count")
    public BizDto.Count unread() { return notify.unread(); }

    @PostMapping("/notifications/{id}/read")
    public BizDto.Notification read(@PathVariable UUID id) { return notify.markRead(id); }

    @PostMapping("/notifications/read-all")
    public BizDto.Count readAll() { return notify.readAll(); }

    @GetMapping("/notification-settings")
    public ObjectNode settings() { return notify.settings(); }

    @PutMapping("/notification-settings")
    public ObjectNode saveSettings(@RequestBody JsonNode body) { return notify.saveSettings(body); }

    /** Chưa gửi kênh ngoài nào nên chưa có lịch sử giao hàng. */
    @GetMapping("/notification-deliveries")
    public BizDto.Page<Object> deliveries() { return new BizDto.Page<>(List.of(), 0, null); }

    /** Telegram/Email chưa được hỗ trợ ở BE: trả 501 rõ ràng thay vì giả vờ thành công. */
    @RequestMapping(path = {"/notification-channels/**", "/notification-deliveries/{id}/resend"}, method = {RequestMethod.POST, RequestMethod.DELETE})
    public void channels() {
        throw new AppException(ErrorCode.NOT_IMPLEMENTED, "Kênh Telegram/Email chưa được hỗ trợ ở BE (chỉ có thông báo trong ứng dụng)");
    }

    // ---- lịch sử hội thoại
    @GetMapping("/conversations")
    public BizDto.Page<BizDto.Conversation> conversations(@RequestParam(required = false) String q, @RequestParam(required = false) UUID agentId,
                                                              @RequestParam(required = false) UUID departmentId, @RequestParam(required = false) UUID projectId,
                                                              @RequestParam(required = false) UUID goalId, @RequestParam(required = false) UUID taskId,
                                                              @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                              @RequestParam(required = false) String role, @RequestParam(required = false) Boolean hasError,
                                                              @RequestParam(required = false) String tool, @RequestParam(required = false) String cursor,
                                                              @RequestParam(defaultValue = "30") int limit) {
        return history.list(new HistoryService.Filter(q, agentId, departmentId, projectId, goalId, taskId, from, to, role, hasError, tool), cursor, clamp(limit, 100));
    }

    @GetMapping("/conversations/{id}")
    public BizDto.Conversation conversation(@PathVariable UUID id) { return history.get(id); }

    @GetMapping("/conversations/{id}/messages")
    public BizDto.Page<BizDto.Message> messages(@PathVariable UUID id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "40") int limit) {
        return history.messages(id, cursor, clamp(limit, 200));
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { history.delete(id); }

    private static int clamp(int n, int max) { return Math.max(1, Math.min(n, max)); }
    private static String value(Object v) { return v == null ? "_" : v.toString(); }
}

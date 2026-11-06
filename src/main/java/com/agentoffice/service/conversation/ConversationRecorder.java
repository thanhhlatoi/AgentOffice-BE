package com.agentoffice.service.conversation;

import com.agentoffice.domain.security.SecretRedactor;
import com.agentoffice.domain.task.TaskFinished;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.util.TextNormalizer;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Khi task kết thúc, ghi lại cuộc hội thoại của nó (đầu vào của chủ + kết quả/lỗi của agent) để màn "Lịch sử" xem lại.
 * Chạy cùng transaction với việc đổi trạng thái task (như các listener khác của TaskFinished).
 *
 * AN TOÀN: nội dung CHỈ được lưu sau khi qua SecretRedactor. Redactor còn là bản "bạn tự viết" (ném UnsupportedOperationException)
 * nên khi chưa viết xong, recorder bỏ qua việc ghi (không bao giờ lưu bản chưa che bí mật). Viết xong redactor là tự ghi.
 */
@Component
public class ConversationRecorder {
    private static final Logger log = LoggerFactory.getLogger(ConversationRecorder.class);
    static final int MAX_CHARS = 20_000;

    private final JdbcTemplate jdbc;
    private final TaskRepository tasks;
    private final SecretRedactor redactor = new SecretRedactor();
    private boolean warned;

    public ConversationRecorder(JdbcTemplate jdbc, TaskRepository tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @EventListener
    @Order(5)
    public void onFinished(TaskFinished e) {
        if (e.status() != TaskStatus.SUCCEEDED && e.status() != TaskStatus.FAILED) return;
        Task t = tasks.findById(e.taskId()).orElse(null);
        if (t == null || t.getAssigneeAgentId() == null) return;
        String input;
        String output;
        boolean failed = e.status() == TaskStatus.FAILED;
        try {
            input = redactor.redact(nz(t.getInput()));
            output = redactor.redact(failed ? nz(t.getError()) : nz(t.getResult()));
        } catch (UnsupportedOperationException notWritten) {
            if (!warned) { warned = true; log.warn("SecretRedactor chưa được viết: BỎ QUA việc ghi lịch sử hội thoại (không lưu nội dung chưa che bí mật)"); }
            return;
        }
        UUID conv = jdbc.queryForObject("""
                INSERT INTO conversation (task_id, agent_id) VALUES (?, ?)
                ON CONFLICT (task_id) WHERE task_id IS NOT NULL DO UPDATE SET agent_id = EXCLUDED.agent_id RETURNING id""",
                UUID.class, t.getId(), t.getAssigneeAgentId());
        jdbc.update("DELETE FROM message WHERE conversation_id = ?", conv);          // chạy lại task: ghi lại từ đầu, không nhân đôi
        long[] u = new long[2];
        jdbc.query("SELECT coalesce(sum(prompt_tokens + completion_tokens), 0), coalesce(sum(cost_micro_usd), 0) FROM usage_record WHERE task_id = ?",
                rs -> { u[0] = rs.getLong(1); u[1] = rs.getLong(2); }, t.getId());
        insert(conv, "USER", input, 0, 0, false);
        if (!output.isBlank()) insert(conv, "ASSISTANT", output, u[0], u[1], failed);
    }

    private void insert(UUID conv, String role, String content, long tokens, long cost, boolean error) {
        boolean cut = content.length() > MAX_CHARS;
        String text = cut ? content.substring(0, MAX_CHARS) : content;
        jdbc.update("""
                INSERT INTO message (conversation_id, role, content, content_search, tokens, cost_micro, has_error, truncated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", conv, role, text, TextNormalizer.forSearch(text), (int) Math.min(tokens, Integer.MAX_VALUE), cost, error, cut);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}

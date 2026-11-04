package com.agentoffice.service.usage;

import com.agentoffice.config.UsageProperties;
import com.agentoffice.domain.event.EventTypes;
import com.agentoffice.domain.usage.BudgetPeriods;
import com.agentoffice.domain.usage.HardAction;
import com.agentoffice.entity.usage.Budget;
import com.agentoffice.domain.usage.BudgetExceededException;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.repository.usage.BudgetRepository;
import com.agentoffice.service.event.EventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đặt chỗ ngân sách NGUYÊN TỬ để nhiều agent chạy song song không vượt hạn mức. MeteredLlmClient gọi:
 *   reserve (trước khi gọi LLM)  ->  settle (có chi phí thật)  |  release (lỗi/huỷ)   — luôn try/finally.
 *
 * Thiết kế:
 * - Mỗi bước (reserve/settle/release) là MỘT transaction RIÊNG (REQUIRES_NEW) nên chỗ đặt hiển thị ngay cho luồng khác,
 *   không phụ thuộc transaction bên ngoài (nếu có).
 * - reserve duyệt chuỗi ngân sách theo thứ tự id cố định (BudgetRepository.findApplicable) nên hai luồng khoá các dòng counter
 *   theo cùng thứ tự => không deadlock. Mỗi ngân sách là một câu UPDATE có điều kiện (không đọc-rồi-ghi); 0 dòng = hết chỗ.
 * - Thất bại ở ngân sách thứ k: hoàn trả 1..k-1 BẰNG CÂU UPDATE rồi ném BudgetExceededException với noRollbackFor, để transaction
 *   vẫn COMMIT (refund + cờ/event budget.exceeded không bị rollback). Luồng khác bị chặn ở dòng counter chỉ thấy trạng thái sau cùng.
 * - Cảnh báo một-lần-mỗi-kỳ bằng UPDATE cờ có điều kiện ("... AND NOT soft_alerted"); chỉ khi đúng 1 dòng đổi mới phát event.
 * - Điều kiện so sánh viết là `spent + reserved <= limit - est` (không phải `spent + reserved + est <= limit`) để est rất lớn không tràn bigint.
 * - est = 0 (model local / chưa có giá) luôn qua: một cuộc gọi không tốn tiền không bị chặn bởi hạn mức tiền.
 * - limitTokens chưa được áp dụng (chỉ spent_tokens được cộng dồn để thống kê).
 */
@Service
public class BudgetGuard {
    /** Một chỗ đã đặt: đủ thông tin để settle/release đúng dòng counter, kể cả khi đã sang kỳ mới trong lúc LLM đang chạy. */
    public record Reservation(UUID budgetId, LocalDate periodStart, long micro) {}

    protected final JdbcTemplate jdbc;
    protected final BudgetRepository budgets;
    protected final EventPublisher events;
    protected final UsageProperties props;
    protected final Clock clock;

    public BudgetGuard(JdbcTemplate jdbc, BudgetRepository budgets, EventPublisher events, UsageProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.budgets = budgets;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    /** @throws BudgetExceededException nếu một ngân sách BLOCK không đủ chỗ (mọi chỗ đã đặt trước đó được hoàn trả) */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BudgetExceededException.class)
    public List<Reservation> reserve(CallContext ctx, long estimateMicro) {
        if (estimateMicro < 0) throw new IllegalArgumentException("estimate không được âm");
        Instant now = clock.instant();
        List<Reservation> done = new ArrayList<>();
        for (Budget b : budgets.findApplicable(ctx.departmentId(), ctx.agentId(), ctx.projectId(), ctx.goalId())) {
            LocalDate period = BudgetPeriods.startOf(b.getPeriod(), now, props.reportZone());
            // nhiều luồng cùng sang kỳ mới: chỉ một luồng tạo được dòng, các luồng khác bỏ qua (không lỗi trùng khoá)
            jdbc.update("INSERT INTO budget_counter (budget_id, period_start) VALUES (?, ?) ON CONFLICT (budget_id, period_start) DO NOTHING",
                    b.getId(), period);
            int n = jdbc.update("""
                    UPDATE budget_counter SET reserved_micro = reserved_micro + ?
                    WHERE budget_id = ? AND period_start = ? AND (? = 0 OR spent_micro + reserved_micro <= ? - ?)""",
                    estimateMicro, b.getId(), period, estimateMicro, b.getLimitMicroUsd(), estimateMicro);
            if (n == 0) {
                announce(b, period, "hard_alerted", EventTypes.BUDGET_EXCEEDED);
                if (b.getHardAction() == HardAction.BLOCK) {
                    refund(done);                                    // không rò chỗ đã đặt ở các ngân sách trước
                    throw new BudgetExceededException(b.getId(), b.getScopeType(), b.getScopeId(), estimateMicro);
                }
                // WARN_ONLY: vẫn cho gọi, vẫn ghi nhận chỗ đặt để settle/release cân bằng
                jdbc.update("UPDATE budget_counter SET reserved_micro = reserved_micro + ? WHERE budget_id = ? AND period_start = ?",
                        estimateMicro, b.getId(), period);
            }
            done.add(new Reservation(b.getId(), period, estimateMicro));
        }
        return done;
    }

    /** reserved -= chỗ đã đặt, spent += chi phí thật (có thể lớn hơn ước tính: chấp nhận vượt nhẹ, không ném lỗi). Dùng kỳ LÚC ĐẶT. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(List<Reservation> reservations, long actualMicro, long actualTokens) {
        if (actualMicro < 0 || actualTokens < 0) throw new IllegalArgumentException("chi phí/token thật không được âm");
        for (Reservation r : reservations) {
            jdbc.update("""
                    UPDATE budget_counter SET reserved_micro = GREATEST(reserved_micro - ?, 0),
                        spent_micro = spent_micro + ?, spent_tokens = spent_tokens + ?
                    WHERE budget_id = ? AND period_start = ?""", r.micro(), actualMicro, actualTokens, r.budgetId(), r.periodStart());
            Budget b = budgets.findById(r.budgetId()).orElse(null);
            if (b == null) continue;
            // spent >= soft% * limit (numeric: không tràn)
            int crossed = jdbc.update("""
                    UPDATE budget_counter SET soft_alerted = TRUE
                    WHERE budget_id = ? AND period_start = ? AND NOT soft_alerted AND spent_micro::numeric * 100 >= ?::numeric * ?::numeric""",
                    r.budgetId(), r.periodStart(), b.getSoftPct(), b.getLimitMicroUsd());
            if (crossed == 1) publish(b, r.periodStart(), EventTypes.BUDGET_WARNING);
        }
    }

    /** Trả lại chỗ đã đặt khi cuộc gọi lỗi/huỷ. Không bao giờ để reserved âm. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(List<Reservation> reservations) { refund(reservations); }

    private void refund(List<Reservation> reservations) {
        for (Reservation r : reservations)
            jdbc.update("UPDATE budget_counter SET reserved_micro = GREATEST(reserved_micro - ?, 0) WHERE budget_id = ? AND period_start = ?",
                    r.micro(), r.budgetId(), r.periodStart());
    }

    /** Cờ nguyên tử: chỉ luồng nào lật được cờ (đúng 1 dòng) mới phát event => một lần mỗi kỳ. `flag` là hằng số của lớp này. */
    private void announce(Budget b, LocalDate period, String flag, String eventType) {
        int flipped = jdbc.update("UPDATE budget_counter SET " + flag + " = TRUE WHERE budget_id = ? AND period_start = ? AND NOT " + flag,
                b.getId(), period);
        if (flipped == 1) publish(b, period, eventType);
    }

    private void publish(Budget b, LocalDate period, String eventType) {
        long spent = jdbc.queryForObject("SELECT spent_micro FROM budget_counter WHERE budget_id = ? AND period_start = ?", Long.class, b.getId(), period);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("budgetId", b.getId());
        p.put("scopeType", b.getScopeType());
        p.put("scopeId", b.getScopeId());
        p.put("period", b.getPeriod());
        p.put("periodStart", period.toString());
        p.put("limitMicroUsd", b.getLimitMicroUsd());
        p.put("spentMicroUsd", spent);
        events.publish(eventType, b.getId(), p);
    }
}

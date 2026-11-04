package com.agentoffice.service.llm;

import com.agentoffice.client.llm.LlmClient;
import com.agentoffice.client.llm.LlmRequest;
import com.agentoffice.client.llm.LlmResponse;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.domain.usage.CostCalculator;
import com.agentoffice.dto.usage.UsageEntry;
import com.agentoffice.entity.usage.ModelPrice;
import com.agentoffice.service.usage.BudgetGuard;
import com.agentoffice.service.usage.CallContextEnricher;
import com.agentoffice.service.usage.PriceService;
import com.agentoffice.service.usage.UsageRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Decorator duy nhất được phép gọi LlmClient thô (ArchitectureTest canh). Mỗi cuộc gọi đều sinh đúng một UsageRecord
 * (kể cả lỗi) và cộng UsageDaily. Đặt ở service/ (không phải client/) vì cần PriceService/UsageRecorder (DB).
 * Gọi delegate NGOÀI mọi transaction của lớp này: không giữ kết nối DB trong lúc chờ LLM.
 */
@Service
public class MeteredLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(MeteredLlmClient.class);

    private final LlmClient delegate;
    private final PriceService prices;
    private final CostCalculator calculator;
    private final UsageRecorder recorder;
    private final BudgetGuard guard;
    private final CallContextEnricher enricher;
    private final Clock clock;

    public MeteredLlmClient(@Qualifier("raw") LlmClient delegate, PriceService prices, CostCalculator calculator,
                            UsageRecorder recorder, BudgetGuard guard, CallContextEnricher enricher, Clock clock) {
        this.delegate = delegate;
        this.prices = prices;
        this.calculator = calculator;
        this.recorder = recorder;
        this.guard = guard;
        this.enricher = enricher;
        this.clock = clock;
    }

    @Override
    public String provider() { return delegate.provider(); }

    /**
     * Thứ tự: giá -> ước tính -> reserve (có thể ném BudgetExceededException, KHÔNG gọi LLM, KHÔNG ghi usage) -> gọi LLM ->
     * ghi usage -> settle theo chi phí thật. Mọi đường thoát khác (lỗi LLM, lỗi đo) đều release hoặc settle bằng ước tính.
     */
    @Override
    public LlmResponse complete(LlmRequest req) {
        CallContext ctx = enricher.enrich(req.context());
        UUID requestId = ctx.requestId() != null ? ctx.requestId() : UUID.randomUUID();
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);   // Postgres chỉ giữ micro giây
        PriceService.Resolved m = prices.resolve(req.model(), delegate.provider());
        Optional<ModelPrice> price = prices.priceAt(m.provider(), m.model(), occurredAt);

        long estimate = estimate(req, price);
        List<BudgetGuard.Reservation> reservations = guard.reserve(ctx, estimate);
        boolean settled = false;
        try {
            long start = System.nanoTime();
            LlmResponse resp = null;
            RuntimeException failure = null;
            try {
                resp = delegate.complete(req);
            } catch (RuntimeException e) {
                failure = e;
            }
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            long actual = estimate;      // đo hỏng sau khi LLM đã trả lời: tính theo ước tính (thận trọng), đừng để rò chỗ đặt
            long tokens = 0;
            try {
                UsageEntry entry = entry(req, ctx, requestId, occurredAt, m, price, resp, failure, latencyMs);
                actual = entry.costMicroUsd();
                tokens = entry.promptTokens() + entry.completionTokens();
                recorder.record(entry);
            } catch (RuntimeException metering) {
                // Tiền đã tiêu, bỏ phản hồi sẽ chỉ khiến lần thử lại tiêu thêm: giữ kết quả, báo lỗi to ở log.
                log.error("KHÔNG ghi được usage requestId={} model={}", requestId, req.model(), metering);
                if (failure != null) failure.addSuppressed(metering);
            }
            if (failure != null) throw failure;      // finally lo release
            settled = true;      // đặt TRƯỚC settle: nếu settle ném giữa chừng thì không release thêm lần nữa (tránh trừ hai lần)
            guard.settle(reservations, actual, tokens);
            return resp;
        } finally {
            if (!settled) guard.release(reservations);
        }
    }

    /** Ước tính chặn trên: token vào (ký tự/4) + TOÀN BỘ maxOutputTokens, theo giá hiện hành. Chưa có giá hoặc model local => 0. */
    private long estimate(LlmRequest req, Optional<ModelPrice> price) {
        if (price.isEmpty()) return 0;
        long in = req.messages().stream().mapToLong(x -> tokensOf(x.content())).sum();
        return calculator.costMicroUsd(in, req.maxOutputTokens(), price.get().getInputMicroPerMtok(), price.get().getOutputMicroPerMtok());
    }

    private UsageEntry entry(LlmRequest req, CallContext ctx, UUID requestId, Instant at, PriceService.Resolved m,
                             Optional<ModelPrice> price, LlmResponse resp, RuntimeException failure, long latencyMs) {
        if (failure != null) {
            // Lỗi: không biết đã tốn bao nhiêu token nên ghi 0 (giới hạn đã biết); vẫn có dòng để thấy tỉ lệ lỗi.
            return new UsageEntry(requestId, at, ctx.agentId(), ctx.taskId(), ctx.goalId(), ctx.projectId(), ctx.departmentId(),
                    m.provider(), m.model(), ctx.purpose().name(), 0, 0, 0, false, 0, null, true, latencyMs, "ERROR",
                    failure.getClass().getSimpleName());
        }
        long in = resp.inputTokens(), out = resp.outputTokens(), cached = Math.min(resp.cachedTokens(), in);
        boolean estimated = in == 0 && out == 0;
        if (estimated) {                                       // nhà cung cấp không báo token => ước lượng ký tự/4
            in = req.messages().stream().mapToLong(x -> tokensOf(x.content())).sum();
            out = tokensOf(resp.text());
            cached = 0;
        }
        long cost = 0;
        if (price.isPresent()) {
            ModelPrice p = price.get();
            long cachedRate = p.getCachedInputMicroPerMtok() != null ? p.getCachedInputMicroPerMtok() : p.getInputMicroPerMtok();
            // inputTokens đã gồm cả token cache: phần cache tính giá cache, phần còn lại tính giá input
            cost = Math.addExact(calculator.costMicroUsd(in - cached, out, p.getInputMicroPerMtok(), p.getOutputMicroPerMtok()),
                    calculator.costMicroUsd(cached, 0, cachedRate, 0));
        }
        return new UsageEntry(requestId, at, ctx.agentId(), ctx.taskId(), ctx.goalId(), ctx.projectId(), ctx.departmentId(),
                m.provider(), m.model(), ctx.purpose().name(), in, out, cached, estimated, cost,
                price.map(ModelPrice::getId).orElse(null), price.isPresent(), latencyMs, "OK", null);
    }

    private static long tokensOf(String s) { return s == null ? 0 : (s.length() + 3) / 4; }
}

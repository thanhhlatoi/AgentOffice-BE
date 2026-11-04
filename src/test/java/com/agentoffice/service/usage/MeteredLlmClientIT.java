package com.agentoffice.service.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.client.llm.LlmClient;
import com.agentoffice.client.llm.LlmRequest;
import com.agentoffice.client.llm.LlmResponse;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.domain.usage.CostCalculator;
import com.agentoffice.dto.usage.ModelPriceDto;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.service.llm.MeteredLlmClient;
import com.agentoffice.support.PostgresIT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** Dây nối đo chi phí trên Postgres thật. */
class MeteredLlmClientIT extends PostgresIT {
    @Autowired PriceService prices;
    @Autowired CostCalculator calc;
    @Autowired UsageRecorder recorder;
    @Autowired com.agentoffice.service.usage.CallContextEnricher enricher;
    @Autowired com.agentoffice.service.usage.BudgetGuard guard;

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private String model;
    private Agent agent;

    @BeforeEach void setUp() {
        model = "m-" + UUID.randomUUID();
        agent = agent("a");
        clock.set(Instant.parse("2026-01-15T03:00:00Z"));
    }

    private void price(long in, long out, Long cached, Instant from, boolean local) {
        prices.add(new ModelPriceDto.Put("testprov", model, in, out, cached, from, local, "test"), "test");
    }

    private MeteredLlmClient client(Function<LlmRequest, LlmResponse> f) {
        LlmClient raw = new LlmClient() {
            @Override public LlmResponse complete(LlmRequest r) { return f.apply(r); }
            @Override public String provider() { return "testprov"; }
        };
        return new MeteredLlmClient(raw, prices, calc, recorder, guard, enricher, clock);
    }

    private LlmRequest req(UUID requestId) {
        return new LlmRequest(model, List.of(new LlmRequest.Msg("user", "xin chào")), 256,
                new CallContext(agent.getId(), null, null, null, null, CallContext.Purpose.AGENT_RUN, requestId));
    }

    private List<Map<String, Object>> records() { return jdbc.queryForList("SELECT * FROM usage_record ORDER BY occurred_at, id"); }

    private Map<String, Object> daily() { return jdbc.queryForMap("SELECT * FROM usage_daily WHERE model = ?", model); }

    @Test void recordsTokensCostAndDailyTotals() {
        price(3_000_000, 15_000_000, null, T0, false);
        client(r -> new LlmResponse("ok", 1000, 500, model)).complete(req(null));
        client(r -> new LlmResponse("ok", 1000, 500, model)).complete(req(null));

        Map<String, Object> rec = records().get(0);
        assertThat(rec.get("cost_micro_usd")).isEqualTo(10_500L);            // 1000*3 + 500*15 = 3000 + 7500
        assertThat(rec).containsEntry("priced", true).containsEntry("status", "OK").containsEntry("estimated", false)
                .containsEntry("prompt_tokens", 1000L).containsEntry("provider", "testprov");
        assertThat(rec.get("price_id")).isNotNull();
        Map<String, Object> d = daily();
        assertThat(d).containsEntry("calls", 2L).containsEntry("cost_micro_usd", 21_000L).containsEntry("prompt_tokens", 2000L);
        assertThat(d.get("day").toString()).isEqualTo("2026-01-15");           // 03:00Z = 10:00 giờ Hồ Chí Minh, cùng ngày
    }

    @Test void dayBoundaryFollowsReportZone() {
        price(1, 1, null, T0, false);
        clock.set(Instant.parse("2026-01-15T18:00:00Z"));                      // = 01:00 ngày 16 giờ Hồ Chí Minh
        client(r -> new LlmResponse("ok", 10, 10, model)).complete(req(null));
        assertThat(daily().get("day").toString()).isEqualTo("2026-01-16");
    }

    @Test void changingPriceKeepsHistoricalCost() {
        price(3_000_000, 15_000_000, null, T0, false);
        client(r -> new LlmResponse("ok", 1000, 0, model)).complete(req(null));       // 3000
        price(6_000_000, 15_000_000, null, Instant.parse("2026-02-01T00:00:00Z"), false);
        clock.set(Instant.parse("2026-02-02T00:00:00Z"));
        client(r -> new LlmResponse("ok", 1000, 0, model)).complete(req(null));       // 6000
        assertThat(records()).extracting(m -> m.get("cost_micro_usd")).containsExactly(3000L, 6000L);
    }

    @Test void localModelCostsZeroButTokensAreCounted() {
        price(0, 0, null, T0, true);
        client(r -> new LlmResponse("ok", 700, 300, model)).complete(req(null));
        assertThat(records().get(0)).containsEntry("cost_micro_usd", 0L).containsEntry("priced", true).containsEntry("prompt_tokens", 700L);
        assertThat(eventTypes()).doesNotContain("usage.unpriced");
    }

    @Test void missingPriceIsNotSilentlyZero() {
        MeteredLlmClient c = client(r -> new LlmResponse("ok", 10, 10, model));
        c.complete(req(null));
        c.complete(req(null));
        assertThat(records()).allSatisfy(m -> assertThat(m).containsEntry("priced", false).containsEntry("cost_micro_usd", 0L));
        assertThat(daily()).containsEntry("unpriced_calls", 2L);
        assertThat(eventTypes().stream().filter("usage.unpriced"::equals)).as("gộp: 1 lần/giờ/model").hasSize(1);

        clock.advance(Duration.ofMinutes(61));
        c.complete(req(null));
        assertThat(eventTypes().stream().filter("usage.unpriced"::equals)).hasSize(2);
    }

    @Test void sameRequestIdIsRecordedOnce() {
        price(1_000_000, 1_000_000, null, T0, false);
        UUID id = UUID.randomUUID();
        MeteredLlmClient c = client(r -> new LlmResponse("ok", 100, 100, model));
        c.complete(req(id));
        c.complete(req(id));
        assertThat(records()).hasSize(1);
        assertThat(daily()).containsEntry("calls", 1L).containsEntry("cost_micro_usd", 200L);
    }

    @Test void failedCallIsStillRecordedAndRethrown() {
        price(1_000_000, 1_000_000, null, T0, false);
        MeteredLlmClient c = client(r -> { throw new IllegalStateException("provider 500"); });
        assertThatThrownBy(() -> c.complete(req(null))).isInstanceOf(IllegalStateException.class).hasMessage("provider 500");
        assertThat(records().get(0)).containsEntry("status", "ERROR").containsEntry("error_code", "IllegalStateException");
        assertThat(daily()).containsEntry("errors", 1L).containsEntry("calls", 1L);
    }

    @Test void providerWithoutTokenCountsIsEstimated() {
        price(1_000_000, 1_000_000, null, T0, false);
        client(r -> new LlmResponse("x".repeat(40), 0, 0, model)).complete(req(null));
        Map<String, Object> rec = records().get(0);
        assertThat(rec).containsEntry("estimated", true).containsEntry("completion_tokens", 10L);   // 40 ký tự / 4
        assertThat((Long) rec.get("prompt_tokens")).isPositive();
    }

    @Test void cachedTokensUseTheCachedRate() {
        price(3_000_000, 0, 300_000L, T0, false);
        client(r -> new LlmResponse("ok", 1000, 0, model, 400)).complete(req(null));
        assertThat(records().get(0)).containsEntry("cost_micro_usd", 1920L)       // 600*3 + 400*0.3 = 1800 + 120
                .containsEntry("cached_tokens", 400L);
    }

    @Test void modelProfileNameResolvesToRealProviderAndModel() {
        LlmRequest r = new LlmRequest("cloud.mid", List.of(new LlmRequest.Msg("user", "hi")), 64,
                new CallContext(agent.getId(), null, null, null, null, CallContext.Purpose.PLAN, null));
        client(x -> new LlmResponse("ok", 10, 10, "cloud.mid")).complete(r);
        assertThat(records().get(0)).containsEntry("provider", "anthropic").containsEntry("model", "claude-sonnet-5-5")
                .containsEntry("priced", true);                                // giá mẫu đã seed ở V9
    }

    @Test void usageCarriesTheProjectOfTheGoalAndTheDepartmentOfTheAgent() {
        price(1_000_000, 1_000_000, null, T0, false);
        UUID project = jdbc.queryForObject("INSERT INTO project(name) VALUES ('p') RETURNING id", UUID.class);
        UUID goal = jdbc.queryForObject("INSERT INTO goal(title, status, project_id) VALUES ('g', 'RUNNING', ?) RETURNING id", UUID.class, project);
        LlmRequest r = new LlmRequest(model, List.of(new LlmRequest.Msg("user", "hi")), 64,
                new CallContext(agent.getId(), null, goal, null, null, CallContext.Purpose.AGENT_RUN, null));
        client(x -> new LlmResponse("ok", 10, 10, model)).complete(r);
        assertThat(records().get(0)).containsEntry("project_id", project);
        assertThat(records().get(0).get("department_id")).isNotNull();
        assertThat(daily().get("project_id")).isEqualTo(project);
    }

    @Test void priceTableIsAppendOnly() {
        price(1, 1, null, T0, false);
        assertThatThrownBy(() -> jdbc.update("UPDATE model_price SET input_micro_per_mtok = 99 WHERE model = ?", model))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM model_price WHERE model = ?", model)).isInstanceOf(DataAccessException.class);
    }

    @Test void newPriceMustComeAfterTheLatestOne() {
        price(1, 1, null, Instant.parse("2026-03-01T00:00:00Z"), false);
        assertThatThrownBy(() -> price(2, 2, null, Instant.parse("2026-02-01T00:00:00Z"), false))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> price(2, 2, null, Instant.parse("2026-03-01T00:00:00Z"), false)).isInstanceOf(AppException.class);
        price(2, 2, null, Instant.parse("2026-04-01T00:00:00Z"), false);
        assertThat(prices.list("testprov", model)).hasSize(2);
        assertThat(prices.list("testprov", model).get(1).getEffectiveTo()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
    }
}

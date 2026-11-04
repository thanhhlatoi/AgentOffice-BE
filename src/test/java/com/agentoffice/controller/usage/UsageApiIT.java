package com.agentoffice.controller.usage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentoffice.client.llm.LlmRequest;
import com.agentoffice.client.llm.LlmResponse;
import com.agentoffice.client.llm.LlmClient;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.domain.usage.CostCalculator;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.service.llm.MeteredLlmClient;
import com.agentoffice.service.usage.PriceService;
import com.agentoffice.service.usage.UsageRecorder;
import com.agentoffice.support.PostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP -> SQL thật: kiểm cả câu truy vấn gộp/keyset lẫn ánh xạ lỗi. */
@AutoConfigureMockMvc
class UsageApiIT extends PostgresIT {
    @Autowired MockMvc mvc;
    @Autowired PriceService prices;
    @Autowired CostCalculator calc;
    @Autowired UsageRecorder recorder;
    @Autowired com.agentoffice.service.usage.CallContextEnricher enricher;
    @Autowired com.agentoffice.service.usage.BudgetGuard guard;

    private String model;
    private Agent agent;

    @BeforeEach void setUp() { model = "m-" + UUID.randomUUID(); agent = agent("a"); }

    private void call(String m, Instant at) {
        clock.set(at);
        LlmClient raw = new LlmClient() {
            @Override public LlmResponse complete(LlmRequest r) { return new LlmResponse("ok", 1000, 1000, m); }
            @Override public String provider() { return "testprov"; }
        };
        new MeteredLlmClient(raw, prices, calc, recorder, guard, enricher, clock).complete(new LlmRequest(m, List.of(new LlmRequest.Msg("user", "x")), 64,
                new CallContext(agent.getId(), null, null, null, null, CallContext.Purpose.AGENT_RUN, null)));
    }

    private String body(long in) {
        return "{\"provider\":\"testprov\",\"model\":\"" + model + "\",\"inputPerMTokMicroUsd\":" + in
                + ",\"outputPerMTokMicroUsd\":2000000,\"effectiveFrom\":\"2026-01-01T00:00:00Z\",\"isLocal\":false}";
    }

    @Test void putPriceThenSecondPutWithSameStartIs409() throws Exception {
        mvc.perform(put("/api/model-prices").contentType(MediaType.APPLICATION_JSON).content(body(1_000_000)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inputPerMTokMicroUsd").value(1_000_000)).andExpect(jsonPath("$.isLocal").value(false));
        mvc.perform(put("/api/model-prices").contentType(MediaType.APPLICATION_JSON).content(body(5_000_000)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(get("/api/model-prices").param("provider", "testprov").param("model", model))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test void putPriceValidatesInput() throws Exception {
        mvc.perform(put("/api/model-prices").contentType(MediaType.APPLICATION_JSON).content(body(-1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test void summaryGroupsByModelAndDayAsIntegers() throws Exception {
        mvc.perform(put("/api/model-prices").contentType(MediaType.APPLICATION_JSON).content(body(1_000_000))).andExpect(status().isOk());
        call(model, Instant.parse("2026-03-10T03:00:00Z"));
        call(model, Instant.parse("2026-03-10T05:00:00Z"));
        call(model, Instant.parse("2026-03-11T03:00:00Z"));
        // mỗi cuộc: 1000 in * 1 + 1000 out * 2 = 3000 micro
        mvc.perform(get("/api/usage/summary").param("from", "2026-03-10").param("to", "2026-03-11").param("groupBy", "day"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("2026-03-10")).andExpect(jsonPath("$[0].calls").value(2))
                .andExpect(jsonPath("$[0].costMicroUsd").value(6000)).andExpect(jsonPath("$[1].costMicroUsd").value(3000));
        mvc.perform(get("/api/usage/summary").param("from", "2026-03-10").param("to", "2026-03-11").param("groupBy", "model"))
                .andExpect(jsonPath("$[0].key").value(model)).andExpect(jsonPath("$[0].calls").value(3));
        mvc.perform(get("/api/usage/summary").param("from", "2026-03-10").param("to", "2026-03-10").param("groupBy", "agent")
                        .param("agentId", agent.getId().toString())).andExpect(jsonPath("$[0].calls").value(2));
    }

    @Test void summaryRejectsBadGroupByAndRange() throws Exception {
        mvc.perform(get("/api/usage/summary").param("from", "2026-03-10").param("to", "2026-03-11").param("groupBy", "x; DROP TABLE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/usage/summary").param("from", "2026-03-11").param("to", "2026-03-10")).andExpect(status().isBadRequest());
    }

    @Test void recordsPageWithKeysetCursorNewestFirstWithoutGapsOrRepeats() throws Exception {
        mvc.perform(put("/api/model-prices").contentType(MediaType.APPLICATION_JSON).content(body(1_000_000))).andExpect(status().isOk());
        Instant base = Instant.parse("2026-03-10T00:00:00Z");
        for (int i = 0; i < 5; i++) call(model, base.plusSeconds(i));
        call(model, base.plusSeconds(4));                                  // cùng occurred_at: cursor phải phân xử bằng id
        var first = mvc.perform(get("/api/usage/records").param("limit", "4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4)).andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = com.jayway.jsonpath.JsonPath.read(first, "$.nextCursor");
        mvc.perform(get("/api/usage/records").param("limit", "4").param("cursor", cursor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(get("/api/usage/records").param("cursor", "rác")).andExpect(status().isBadRequest());
    }

    @Test void unpricedListsModelsWithoutAnOpenPrice() throws Exception {
        call(model, Instant.parse("2026-03-10T03:00:00Z"));
        mvc.perform(get("/api/usage/unpriced")).andExpect(status().isOk()).andExpect(jsonPath("$[0].model").value(model))
                .andExpect(jsonPath("$[0].calls").value(1));
        Instant later = Instant.parse("2026-03-12T00:00:00Z");
        prices.add(new com.agentoffice.dto.usage.ModelPriceDto.Put("testprov", model, 1L, 1L, null, later, false, null), "test");
        mvc.perform(get("/api/usage/unpriced")).andExpect(jsonPath("$.length()").value(0));
    }
}

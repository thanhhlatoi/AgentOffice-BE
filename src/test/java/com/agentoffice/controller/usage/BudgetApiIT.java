package com.agentoffice.controller.usage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.support.PostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class BudgetApiIT extends PostgresIT {
    @Autowired MockMvc mvc;

    private String create(Agent a, long limit) throws Exception {
        String res = mvc.perform(post("/api/budgets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"AGENT\",\"scopeId\":\"" + a.getId() + "\",\"period\":\"TOTAL\",\"limitMicroUsd\":" + limit + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.limitMicroUsd").value(limit)).andExpect(jsonPath("$.softPct").value(80))
                .andExpect(jsonPath("$.hardAction").value("BLOCK")).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(res, "$.id");
    }

    @Test void createStatusRaiseAndHistory() throws Exception {
        Agent a = agent("a");
        String id = create(a, 1000);
        mvc.perform(get("/api/budgets/" + id + "/status")).andExpect(status().isOk()).andExpect(jsonPath("$.remainingMicroUsd").value(1000))
                .andExpect(jsonPath("$.usedPct").value(0));
        mvc.perform(post("/api/budgets/" + id + "/raise").contentType(MediaType.APPLICATION_JSON).content("{\"newLimit\":500,\"reason\":\"giảm?\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
        mvc.perform(post("/api/budgets/" + id + "/raise").contentType(MediaType.APPLICATION_JSON).content("{\"newLimit\":2000,\"reason\":\"cần thêm\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.limitMicroUsd").value(2000));
        mvc.perform(get("/api/budgets/" + id + "/changes")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].oldLimitMicroUsd").value(1000)).andExpect(jsonPath("$[0].newLimitMicroUsd").value(2000));
    }

    @Test void validationAndConflicts() throws Exception {
        Agent a = agent("a");
        String id = create(a, 1000);
        mvc.perform(post("/api/budgets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopeType\":\"AGENT\",\"scopeId\":\"" + a.getId() + "\",\"period\":\"TOTAL\",\"limitMicroUsd\":5}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/budgets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopeType\":\"GLOBAL\",\"period\":\"TOTAL\",\"limitMicroUsd\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/budgets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopeType\":\"GLOBAL\",\"period\":\"TOTAL\",\"limitMicroUsd\":5,\"softPct\":101}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/budgets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopeType\":\"AGENT\",\"scopeId\":\"" + UUID.randomUUID() + "\",\"period\":\"DAY\",\"limitMicroUsd\":5}")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/budgets/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"softPct\":50,\"hardAction\":\"WARN_ONLY\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.softPct").value(50)).andExpect(jsonPath("$.hardAction").value("WARN_ONLY"));
        mvc.perform(delete("/api/budgets/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/budgets/" + id)).andExpect(status().isNotFound());
    }
}

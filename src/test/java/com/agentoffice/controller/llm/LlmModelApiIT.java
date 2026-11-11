package com.agentoffice.controller.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentoffice.support.PostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** provider=fake (mặc định test): onGateway không xác định; test model chạy qua MeteredLlmClient thật và ghi usage. */
@AutoConfigureMockMvc
class LlmModelApiIT extends PostgresIT {
    @Autowired MockMvc mvc;

    @Test void profilesListSeededRowsWithStatus() throws Exception {
        mvc.perform(get("/api/model-profiles")).andExpect(status().isOk())
                // 5 hồ sơ seed của V8; ModelsApiIT có thể đã thêm hồ sơ khác (model_profile không bị dọn giữa các test)
                .andExpect(jsonPath("$[?(@.name in ['local.small','local.strong','cloud.cheap','cloud.mid','cloud.strong'])]").value(org.hamcrest.Matchers.hasSize(5)))
                .andExpect(jsonPath("$[?(@.name=='cloud.cheap')].escalateTo").value("cloud.mid"))
                .andExpect(jsonPath("$[?(@.name=='cloud.strong')].priced").value(true))   // V9 seed giá mẫu
                .andExpect(jsonPath("$[0].onGateway").doesNotExist());                     // null bị bỏ (non_null)
    }

    @Test void modelTestRecordsUsageAndReportsOk() throws Exception {
        mvc.perform(post("/api/llm/models/test").contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"writer\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.model").value("writer"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_record WHERE purpose = 'MODEL_TEST' AND model = 'writer'", Long.class)).isEqualTo(1);
    }

    @Test void modelTestRejectsBlankModel() throws Exception {
        mvc.perform(post("/api/llm/models/test").contentType(MediaType.APPLICATION_JSON).content("{\"model\":\" \"}")).andExpect(status().isBadRequest());
    }

    @Test void providersEmptyWhenFake() throws Exception {
        mvc.perform(get("/api/llm/providers")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    /** FE (api/gateway.ts) gọi với base /api/fe: cùng endpoint phải trả được ở /api/fe/llm. */
    @Test void sameEndpointsServedUnderFePrefix() throws Exception {
        mvc.perform(get("/api/fe/llm/providers")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/fe/llm/models/test").contentType(MediaType.APPLICATION_JSON).content("{\"model\":\" \"}")).andExpect(status().isBadRequest());
    }
}

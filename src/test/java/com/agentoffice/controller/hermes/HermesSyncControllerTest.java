package com.agentoffice.controller.hermes;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentoffice.service.hermes.HermesSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Endpoint skill của bot (tab Skill trong FE): kiểm lớp web, service là mock. */
@WebMvcTest(HermesSyncController.class)
class HermesSyncControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean HermesSyncService service;

    private final UUID id = UUID.randomUUID();
    private static final String J = MediaType.APPLICATION_JSON_VALUE;

    @Test void listSkillsWorksUnderBothPrefixes() throws Exception {
        when(service.skills(id)).thenReturn(json.readTree("[{\"name\":\"obsidian\",\"enabled\":false}]"));
        mvc.perform(get("/api/fe/agents/" + id + "/hermes/skills")).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("obsidian"));
        mvc.perform(get("/api/agents/" + id + "/hermes/skills")).andExpect(status().isOk());
    }

    @Test void bulkToggleIsForwarded() throws Exception {
        when(service.setSkillsEnabled(eq(id), any())).thenReturn(json.readTree("{\"changed\":[\"a\"],\"skills\":[]}"));
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/skills").contentType(J).content("{\"enabled\":{\"a\":false}}"))
                .andExpect(status().isOk());
        verify(service).setSkillsEnabled(eq(id), any());
    }

    @Test void badBodiesAreRejectedBeforeReachingHermes() throws Exception {
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/skills").contentType(J).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/skills").contentType(J).content("{\"enabled\":{\"a\":\"yes\"}}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/skills").contentType(J).content("{\"enabled\":{\"../x\":true}}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/skills/obsidian").contentType(J).content("{\"name\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/skills/obsidian").contentType(J).content("{\"content\":5}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void updateSkillForwardsContent() throws Exception {
        when(service.updateSkill(eq(id), eq("obsidian"), any())).thenReturn(json.readTree("{\"changed\":[\"content\"]}"));
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/skills/obsidian").contentType(J).content("{\"content\":\"---\\nname: obsidian\\n---\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed[0]").value("content"));
    }

    @Test void botConfigReadAndRestrictedEdit() throws Exception {
        when(service.bot(id)).thenReturn(json.readTree("{\"name\":\"b\",\"model\":\"qwen\",\"envKeys\":[]}"));
        mvc.perform(get("/api/fe/agents/" + id + "/hermes/bot")).andExpect(status().isOk()).andExpect(jsonPath("$.model").value("qwen"));
        when(service.updateBot(eq(id), any())).thenReturn(json.readTree("{\"changed\":[\"model\"]}"));
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/bot").contentType(J).content("{\"model\":\"qwen3\",\"contextLength\":8192}")).andExpect(status().isOk());
        // soul/description không sửa được ở đây; sai kiểu cũng bị chặn trước khi tới Hermes
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/bot").contentType(J).content("{\"soul\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/bot").contentType(J).content("{\"contextLength\":\"lớn\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/fe/agents/" + id + "/hermes/bot").contentType(J).content("{}")).andExpect(status().isBadRequest());
    }
}

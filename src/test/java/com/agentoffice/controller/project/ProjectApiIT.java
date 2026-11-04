package com.agentoffice.controller.project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentoffice.support.PostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ProjectApiIT extends PostgresIT {
    @Autowired MockMvc mvc;

    private String create(String name) throws Exception {
        String res = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"color\":\"#00FF88\",\"deadline\":\"2027-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.progressPct").value(0))
                .andExpect(jsonPath("$.hasPlan").value(false)).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(res, "$.id");
    }

    @Test void createGetPatchArchiveAndValidation() throws Exception {
        String id = create("Kênh Reddit");
        mvc.perform(get("/api/projects/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.project.name").value("Kênh Reddit"))
                .andExpect(jsonPath("$.tasksByStatus.QUEUED").value(0)).andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.costMicroUsd").value(0)).andExpect(jsonPath("$.goals.length()").value(0));
        mvc.perform(patch("/api/projects/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAUSED\",\"color\":\"#123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED")).andExpect(jsonPath("$.color").value("#123456"));
        mvc.perform(patch("/api/projects/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/projects").param("status", "PAUSED")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/projects/" + id + "/archive")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(patch("/api/projects/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isConflict());

        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"color\":\"đỏ\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"ownerAgentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test void moveGoalAndProgressHistory() throws Exception {
        String p = create("P");
        UUID g = jdbc.queryForObject("INSERT INTO goal(title, status) VALUES ('g', 'RUNNING') RETURNING id", UUID.class);
        mvc.perform(put("/api/goals/" + g + "/project").contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"" + p + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value(p));
        mvc.perform(get("/api/projects/" + p)).andExpect(jsonPath("$.goals.length()").value(1));
        mvc.perform(put("/api/goals/" + g + "/project").contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/" + p + "/progress-history").param("from", "2026-01-01").param("to", "2026-01-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/projects/" + p + "/progress-history").param("from", "2026-01-03").param("to", "2026-01-02")).andExpect(status().isBadRequest());
    }
}

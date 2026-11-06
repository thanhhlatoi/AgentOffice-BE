package com.agentoffice.controller.fe;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentoffice.dto.fe.OfficeDto;
import com.agentoffice.service.fe.CommandService;
import com.agentoffice.service.fe.ViewService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Đường dẫn và mã trạng thái của lớp /api/fe (service là mock: chỉ kiểm lớp web). */
@WebMvcTest(OfficeController.class)
class OfficeControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ViewService view;
    @MockBean CommandService cmd;
    @MockBean com.agentoffice.service.department.DepartmentService departments;

    private final UUID id = UUID.randomUUID();
    private static final String J = MediaType.APPLICATION_JSON_VALUE;

    @Test void systemStateUsesFrontendShape() throws Exception {
        when(view.systemState()).thenReturn(new OfficeDto.SystemState("PAUSED", null, 7));
        mvc.perform(get("/api/fe/system")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PAUSED")).andExpect(jsonPath("$.lastEventId").value(7));
    }

    @Test void createAgentReturns201WithProfile() throws Exception {
        when(cmd.createAgent(any())).thenReturn(id);
        when(view.agent(id)).thenReturn(new OfficeDto.Agent(id.toString(), "Linh", "Planner", "default", "", "#2563eb",
                "IDLE", null, null, false, null, List.of("sql")));
        mvc.perform(post("/api/fe/agents").contentType(J).content("{\"name\":\"Linh\",\"role\":\"Planner\",\"skills\":[\"sql\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Linh"))
                .andExpect(jsonPath("$.skills[0]").value("sql"));
    }

    @Test void createAgentWithoutNameIs400() throws Exception {
        mvc.perform(post("/api/fe/agents").contentType(J).content("{\"role\":\"x\"}")).andExpect(status().isBadRequest());
    }

    @Test void approveAndRejectGoToCommands() throws Exception {
        when(view.approval(id)).thenReturn(new OfficeDto.Approval(id.toString(), ViewService.NO_AGENT, "p", null, "EXTERNAL",
                null, null, "x", java.util.Map.of(), null, "APPROVED", null, null));
        mvc.perform(post("/api/fe/approvals/" + id + "/approve")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        verify(cmd).approve(id);
        mvc.perform(post("/api/fe/approvals/" + id + "/reject").contentType(J).content("{\"reason\":\"sai\"}")).andExpect(status().isOk());
        verify(cmd).reject(eq(id), eq("sai"));
    }

    @Test void priorityAndGoalActionsRoute() throws Exception {
        when(cmd.setPriority(eq(id), eq("HIGH"))).thenReturn(id);
        mvc.perform(put("/api/fe/tasks/" + id + "/priority").contentType(J).content("{\"priority\":\"HIGH\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/fe/goals/" + id + "/approve-plan")).andExpect(status().isOk());
        verify(cmd).approvePlan(id);
    }

    @Test void deleteAgentArchivesAndReturns204() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/fe/agents/" + id))
                .andExpect(status().isNoContent());
        verify(cmd).deleteAgent(id);
    }
}

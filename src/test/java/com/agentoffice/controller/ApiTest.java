package com.agentoffice.controller;

import com.agentoffice.controller.approval.ApprovalController;
import com.agentoffice.controller.approval.ApprovalPolicyController;
import com.agentoffice.controller.task.GoalController;
import com.agentoffice.controller.task.TaskController;
import com.agentoffice.domain.approval.ApprovalStatus;
import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.entity.approval.Approval;
import com.agentoffice.entity.approval.ApprovalPolicy;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.approval.ApprovalRepository;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.service.approval.ApprovalGate;
import com.agentoffice.service.approval.ApprovalPolicyService;
import com.agentoffice.service.task.GoalService;
import com.agentoffice.service.task.TaskService;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.agentoffice.exception.NotFoundException;
import com.agentoffice.exception.UnprocessableException;
import com.agentoffice.service.task.OrchestratorService;
import com.agentoffice.controller.system.SystemController;
import com.agentoffice.service.system.SystemStateService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Mã trạng thái 200/201/400/404/409/413/422 của từng endpoint. Service là mock: chỉ kiểm lớp web + ánh xạ lỗi. */
@WebMvcTest(controllers = {GoalController.class, TaskController.class, SystemController.class,
        ApprovalController.class, ApprovalPolicyController.class})
@org.springframework.context.annotation.Import({com.agentoffice.mapper.task.TaskMapperImpl.class, com.agentoffice.mapper.task.GoalMapperImpl.class,
        com.agentoffice.mapper.approval.ApprovalMapperImpl.class})
class ApiTest {
    @Autowired MockMvc mvc;
    @MockBean GoalService goals;
    @MockBean OrchestratorService orchestrator;
    @MockBean TaskService tasks;
    @MockBean SystemStateService system;
    @MockBean TaskRepository taskRepo;
    @MockBean ApprovalRepository approvals;
    @MockBean ApprovalGate gate;
    @MockBean ApprovalPolicyService policies;

    private static final String J = MediaType.APPLICATION_JSON_VALUE;
    private final UUID id = UUID.randomUUID();

    private Goal goal() { return new Goal("t", "d", null, null); }
    private Task task() { return new Task(null, null, id, "HUMAN", "t", "i", Priority.NORMAL, 0); }

    // ------------------------------------------------------------ lỗi chuẩn + requestId
    @Test void everyErrorCarriesTheRequestIdFromTheHeader() throws Exception {
        when(goals.detail(id)).thenThrow(new NotFoundException("goal", id));
        mvc.perform(get("/api/goals/" + id).header("X-Request-Id", "req-42")).andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "req-42")).andExpect(jsonPath("$.requestId").value("req-42"));
        mvc.perform(post("/api/goals").contentType(J).content("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test void springMvcErrorsAlsoUseApiError() throws Exception {
        mvc.perform(get("/api/khong-ton-tai")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(delete("/api/goals")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/goals").contentType("text/plain").content("x")).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test void unexpectedExceptionsAre500WithoutLeakingInternals() throws Exception {
        when(goals.list()).thenThrow(new RuntimeException("password=hunter2 jdbc://internal"));
        mvc.perform(get("/api/goals")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.message").value("Lỗi nội bộ")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))));
    }

    // ------------------------------------------------------------ goals
    @Test void createGoalIs201AndStartsPlanning() throws Exception {
        when(goals.create(any(), any(), any(), any())).thenReturn(goal());
        mvc.perform(post("/api/goals").contentType(J).content("{\"title\":\"Lập báo cáo\",\"description\":\"x\",\"tokenBudget\":1000}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"));
        verify(orchestrator).planAsync(any(), eq(List.of()));
    }

    @Test void createGoalValidation() throws Exception {
        for (String body : new String[] {"{}", "{\"title\":\"\"}", "{\"title\":\"" + "x".repeat(301) + "\"}",
                "{\"title\":\"a\",\"tokenBudget\":-1}", "{\"title\":", "{\"title\":\"a\",\"departmentId\":\"khong-phai-uuid\"}"})
            mvc.perform(post("/api/goals").contentType(J).content(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test void unknownGoalIs404() throws Exception {
        when(goals.detail(id)).thenThrow(new NotFoundException("goal", id));
        mvc.perform(get("/api/goals/" + id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/goals/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test void goalListIs200() throws Exception {
        when(goals.list()).thenReturn(List.of());
        mvc.perform(get("/api/goals")).andExpect(status().isOk());
    }

    @Test void cancelAndPlanConflictsAre409() throws Exception {
        doThrow(new IllegalStateException("đã kết thúc")).when(orchestrator).cancelGoal(eq(id), any());
        mvc.perform(post("/api/goals/" + id + "/cancel")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        doThrow(new IllegalStateException("không có kế hoạch chờ")).when(orchestrator).approvePlan(eq(id), any(), any());
        mvc.perform(post("/api/goals/" + id + "/plan/approve")).andExpect(status().isConflict());
    }

    @Test void rejectPlanNeedsAReasonAndReplansWhenAllowed() throws Exception {
        mvc.perform(post("/api/goals/" + id + "/plan/reject").contentType(J).content("{}")).andExpect(status().isBadRequest());
        when(orchestrator.rejectPlan(eq(id), any(), eq("chưa đủ"))).thenReturn(new OrchestratorService.RejectOutcome(true));
        when(goals.get(id)).thenReturn(goal());
        mvc.perform(post("/api/goals/" + id + "/plan/reject").contentType(J).content("{\"reason\":\"chưa đủ\"}")).andExpect(status().isOk());
        verify(orchestrator).planAsync(id, List.of("chưa đủ"));
    }

    // ------------------------------------------------------------ tasks
    @Test void createTaskIs201() throws Exception {
        Task t = task();
        when(tasks.create(any())).thenReturn(t);
        when(tasks.settle(any())).thenReturn(t);
        mvc.perform(post("/api/agents/" + id + "/tasks").contentType(J).content("{\"title\":\"Làm\",\"input\":\"x\",\"priority\":\"HIGH\"}"))
                .andExpect(status().isCreated());
    }

    @Test void createTaskValidationAndDomainErrors() throws Exception {
        String url = "/api/agents/" + id + "/tasks";
        mvc.perform(post(url).contentType(J).content("{\"input\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(J).content("{\"title\":\"a\",\"priority\":\"URGENT\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(J).content("{\"title\":\"a\",\"input\":\"" + "x".repeat(20001) + "\"}")).andExpect(status().isBadRequest());
        doThrow(new NotFoundException("agent", id)).when(tasks).create(any());
        mvc.perform(post(url).contentType(J).content("{\"title\":\"a\"}")).andExpect(status().isNotFound());
        doThrow(new UnprocessableException("Agent OFFLINE")).when(tasks).create(any());
        mvc.perform(post(url).contentType(J).content("{\"title\":\"a\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
    }

    @Test void oversizedBodyIs413() throws Exception {
        mvc.perform(post("/api/agents/" + id + "/tasks").contentType(J).content("{\"title\":\"a\",\"input\":\"" + "x".repeat(300_000) + "\"}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test void listTasksFiltersAndPagingBounds() throws Exception {
        when(tasks.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(task())));
        mvc.perform(get("/api/tasks?status=QUEUED&agentId=" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/tasks?from=2026-11-01&to=2026-11-12&q=abc")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/tasks?from=11-2026")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks?status=NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks?size=1000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks?page=-1")).andExpect(status().isBadRequest());
    }

    @Test void taskActionsMapStateErrorsTo409() throws Exception {
        when(tasks.cancel(id)).thenThrow(new IllegalStateException("Task đã kết thúc"));
        mvc.perform(post("/api/tasks/" + id + "/cancel")).andExpect(status().isConflict());
        when(tasks.retry(id)).thenThrow(new IllegalStateException("Chỉ thử lại được task FAILED"));
        mvc.perform(post("/api/tasks/" + id + "/retry")).andExpect(status().isConflict());
        when(tasks.get(id)).thenThrow(new NotFoundException("task", id));
        mvc.perform(get("/api/tasks/" + id)).andExpect(status().isNotFound());
    }

    @Test void patchPriority() throws Exception {
        when(tasks.changePriority(eq(id), eq(Priority.LOW))).thenReturn(task());
        mvc.perform(patch("/api/tasks/" + id).contentType(J).content("{\"priority\":\"LOW\"}")).andExpect(status().isOk());
        mvc.perform(patch("/api/tasks/" + id).contentType(J).content("{}")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ system
    @Test void systemPauseResumeState() throws Exception {
        when(system.isPaused()).thenReturn(true);
        mvc.perform(get("/api/system/state")).andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(true));
        mvc.perform(post("/api/system/pause")).andExpect(status().isOk());
        mvc.perform(post("/api/system/resume")).andExpect(status().isOk());
        verify(system).pause("owner");
        verify(system).resume("owner");
    }

    // ------------------------------------------------------------ approvals
    private Approval tool() { return Approval.toolCall(id, id, "send_email", RiskLevel.EXTERNAL, "{}", "p", "k", java.time.Instant.now()); }
    private Approval plan() { return Approval.plan(id, "{}", "p", "k", java.time.Instant.now()); }

    @Test void listApprovalsDefaultsToPending() throws Exception {
        when(approvals.findByStatusOrderByCreatedAtAsc(ApprovalStatus.PENDING)).thenReturn(List.of(tool()));
        mvc.perform(get("/api/approvals?status=PENDING")).andExpect(status().isOk()).andExpect(jsonPath("$[0].toolName").value("send_email"));
        mvc.perform(get("/api/approvals")).andExpect(status().isOk());
        mvc.perform(get("/api/approvals?status=XYZ")).andExpect(status().isBadRequest());
    }

    @Test void approveRoutesByKind() throws Exception {
        when(approvals.findById(id)).thenReturn(Optional.of(tool()));
        mvc.perform(post("/api/approvals/" + id + "/approve").contentType(J).content("{\"note\":\"ok\"}")).andExpect(status().isOk());
        verify(gate).approve(id, "owner", "ok");
        UUID planId = UUID.randomUUID();
        Approval p = plan();
        when(approvals.findById(planId)).thenReturn(Optional.of(p));
        mvc.perform(post("/api/approvals/" + planId + "/approve")).andExpect(status().isOk());
        verify(orchestrator).approvePlan(id, "owner", null);
    }

    @Test void approvalErrors() throws Exception {
        mvc.perform(post("/api/approvals/" + id + "/approve")).andExpect(status().isNotFound());
        when(approvals.findById(id)).thenReturn(Optional.of(tool()));
        doThrow(new IllegalStateException("đã duyệt")).when(gate).approve(eq(id), any(), any());
        mvc.perform(post("/api/approvals/" + id + "/approve")).andExpect(status().isConflict());
        mvc.perform(post("/api/approvals/" + id + "/reject").contentType(J).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/approvals/" + id + "/reject").contentType(J).content("{\"reason\":\"không\"}")).andExpect(status().isOk());
        verify(gate).reject(id, "owner", "không");
    }

    // ------------------------------------------------------------ policies
    @Test void policiesListAndUpdate() throws Exception {
        when(policies.list()).thenReturn(List.of(new ApprovalPolicy(null, RiskLevel.EXTERNAL, true)));
        mvc.perform(get("/api/approval-policies")).andExpect(status().isOk()).andExpect(jsonPath("$[0].riskLevel").value("EXTERNAL"));
        when(policies.put(any(), eq(RiskLevel.READ), eq(false), any())).thenReturn(new ApprovalPolicy(null, RiskLevel.READ, false));
        mvc.perform(put("/api/approval-policies").contentType(J).content("{\"riskLevel\":\"READ\",\"requiresApproval\":false}")).andExpect(status().isOk());
        mvc.perform(put("/api/approval-policies").contentType(J).content("{\"requiresApproval\":false}")).andExpect(status().isBadRequest());
    }

    @Test void turningOffMoneyApprovalIs422() throws Exception {
        when(policies.put(any(), eq(RiskLevel.MONEY), eq(false), any())).thenThrow(new UnprocessableException("Không được tắt duyệt cho mức rủi ro MONEY"));
        mvc.perform(put("/api/approval-policies").contentType(J).content("{\"riskLevel\":\"MONEY\",\"requiresApproval\":false}"))
                .andExpect(status().isUnprocessableEntity());
    }
}

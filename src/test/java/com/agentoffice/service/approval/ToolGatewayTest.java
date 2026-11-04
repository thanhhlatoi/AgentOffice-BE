package com.agentoffice.service.approval;

import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.domain.tool.Tool;
import com.agentoffice.domain.tool.ToolSpec;
import com.agentoffice.service.tool.ToolRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.agentoffice.exception.NotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolGatewayTest {
    private final ApprovalPolicyService policies = mock(ApprovalPolicyService.class);
    private final ApprovalGate gate = mock(ApprovalGate.class);
    private final Tool tool = mock(Tool.class);
    private final ToolGateway gw;
    private final UUID task = UUID.randomUUID(), agent = UUID.randomUUID();

    ToolGatewayTest() {
        when(tool.spec()).thenReturn(new ToolSpec("send_email", RiskLevel.EXTERNAL));
        gw = new ToolGateway(new ToolRegistry(List.of(tool)), policies, gate, new ObjectMapper());
    }

    @Test void toolNeedingApprovalIsNeverExecutedByTheGateway() {
        UUID approval = UUID.randomUUID();
        when(policies.requiresApproval("send_email", RiskLevel.EXTERNAL)).thenReturn(true);
        when(gate.request(eq(task), eq(agent), eq("send_email"), eq(RiskLevel.EXTERNAL), anyString())).thenReturn(approval);
        var r = gw.invoke(task, agent, "send_email", "{\"to\":\"a@b.c\"}");
        assertThat(r.status()).isEqualTo(ToolGateway.Status.PENDING_APPROVAL);
        assertThat(r.approvalId()).isEqualTo(approval);
        verify(tool, never()).execute(any(), anyString());
    }

    @Test void riskComesFromTheToolSpecNotFromTheCaller() {
        when(policies.requiresApproval("send_email", RiskLevel.EXTERNAL)).thenReturn(true);
        gw.invoke(task, agent, "send_email", "{}");
        verify(policies).requiresApproval("send_email", RiskLevel.EXTERNAL);
    }

    @Test void toolWithoutApprovalRunsImmediatelyWithAStableKey() {
        when(policies.requiresApproval(anyString(), any())).thenReturn(false);
        when(tool.execute(any(JsonNode.class), anyString())).thenReturn("done");
        var r = gw.invoke(task, agent, "send_email", "{\"b\":1,\"a\":2}");
        assertThat(r.status()).isEqualTo(ToolGateway.Status.EXECUTED);
        assertThat(r.output()).isEqualTo("done");
        verify(gate, never()).request(any(), any(), anyString(), any(), anyString());
    }

    @Test void unknownToolIsRejected() {
        assertThatThrownBy(() -> gw.invoke(task, agent, "rm_rf", "{}")).isInstanceOf(NotFoundException.class);
    }

    @Test void duplicateToolNamesAreRejectedAtStartup() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool, tool))).isInstanceOf(IllegalStateException.class);
    }
}

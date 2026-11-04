package com.agentoffice.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.usage.BudgetPeriodType;
import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.domain.usage.HardAction;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.approval.ApprovalPolicy;
import com.agentoffice.entity.project.Project;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.usage.Budget;
import com.agentoffice.entity.usage.BudgetChange;
import com.agentoffice.mapper.agent.AgentMapperImpl;
import com.agentoffice.mapper.approval.ApprovalMapperImpl;
import com.agentoffice.mapper.project.ProjectMapperImpl;
import com.agentoffice.mapper.task.GoalMapperImpl;
import com.agentoffice.mapper.task.TaskMapperImpl;
import com.agentoffice.mapper.usage.BudgetMapperImpl;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mapper do MapStruct sinh: kiểm các trường đổi tên, boolean is*, danh sách, null. (Thiếu trường đã bị chặn lúc biên dịch.) */
class EntityMappersTest {
    @Test void task() {
        Task t = new Task(UUID.randomUUID(), null, UUID.randomUUID(), "HUMAN", "tiêu đề", "in", Priority.HIGH, 1);
        t.setCancelRequested(true);
        t.setWeight(4);
        t.setLabels(List.of("gấp"));
        var d = new TaskMapperImpl().toDto(t);
        assertThat(d.title()).isEqualTo("tiêu đề");
        assertThat(d.priority()).isEqualTo(Priority.HIGH);
        assertThat(d.cancelRequested()).isTrue();
        assertThat(d.weight()).isEqualTo(4);
        assertThat(d.labels()).containsExactly("gấp").isNotSameAs(t.getLabels());   // bản sao, sửa DTO không đụng entity
        assertThat(d.goalId()).isEqualTo(t.getGoalId());
        assertThat(new TaskMapperImpl().toDto(null)).isNull();
    }

    @Test void goalAndAgent() {
        Goal g = new Goal("Báo cáo", "mô tả", null, 1000L);
        assertThat(new GoalMapperImpl().toDto(g).tokenBudget()).isEqualTo(1000L);
        Agent a = new Agent("Linh", "Writer", "Content");
        var ad = new AgentMapperImpl().toDto(a);
        assertThat(ad.name()).isEqualTo("Linh");
        assertThat(ad.status()).isEqualTo("IDLE");
        assertThat(new AgentMapperImpl().toDtos(List.of(a, a))).hasSize(2);
    }

    @Test void budgetAndRenamedChangeFields() {
        Budget b = new Budget(BudgetScope.GLOBAL, null, BudgetPeriodType.DAY, 5_000_000, null, 80, HardAction.BLOCK);
        var v = new BudgetMapperImpl().toView(b);
        assertThat(v.limitMicroUsd()).isEqualTo(5_000_000);
        assertThat(v.enabled()).isTrue();
        var c = new BudgetMapperImpl().toChange(new BudgetChange(UUID.randomUUID(), 100, 300, "tăng", "owner", Instant.EPOCH));
        assertThat(c.oldLimitMicroUsd()).isEqualTo(100);
        assertThat(c.newLimitMicroUsd()).isEqualTo(300);
        assertThat(c.changedBy()).isEqualTo("owner");
    }

    @Test void projectAndPolicyBooleans() {
        Project p = new Project("P", null, null, null, "#112233", null);
        p.setHasPlan(true);
        p.setProgressPct(42);
        var v = new ProjectMapperImpl().toView(p);
        assertThat(v.hasPlan()).isTrue();
        assertThat(v.progressPct()).isEqualTo(42);
        assertThat(v.color()).isEqualTo("#112233");
        var pol = new ApprovalMapperImpl().toDto(new ApprovalPolicy("send_email", RiskLevel.EXTERNAL, true));
        assertThat(pol.requiresApproval()).isTrue();
        assertThat(pol.toolName()).isEqualTo("send_email");
    }
}

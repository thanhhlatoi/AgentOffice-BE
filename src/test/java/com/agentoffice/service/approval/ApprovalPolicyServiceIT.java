package com.agentoffice.service.approval;

import com.agentoffice.domain.approval.RiskLevel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.exception.UnprocessableException;
import com.agentoffice.support.PlumbingIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ApprovalPolicyServiceIT extends PlumbingIT {
    @Autowired ApprovalPolicyService policies;

    @Test void defaultsFollowTheRiskTable() {
        assertThat(policies.requiresApproval("x", RiskLevel.READ)).isFalse();
        assertThat(policies.requiresApproval("x", RiskLevel.WRITE_INTERNAL)).isFalse();
        assertThat(policies.requiresApproval("x", RiskLevel.EXTERNAL)).isTrue();
        assertThat(policies.requiresApproval("x", RiskLevel.MONEY)).isTrue();
        assertThat(policies.requiresApproval("x", RiskLevel.DESTRUCTIVE)).isTrue();
    }

    @Test void moneyAndDestructiveCannotBeSwitchedOff() {
        for (RiskLevel r : new RiskLevel[] {RiskLevel.MONEY, RiskLevel.DESTRUCTIVE}) {
            assertThatThrownBy(() -> policies.put(null, r, false, "owner")).isInstanceOf(UnprocessableException.class);
            assertThatThrownBy(() -> policies.put("pay", r, false, "owner")).isInstanceOf(UnprocessableException.class);
            assertThat(policies.requiresApproval("pay", r)).isTrue();
        }
    }

    @Test void moneyIsStillAskedEvenIfTheDatabaseSaysOtherwise() {
        jdbc.execute("ALTER TABLE approval_policy DISABLE TRIGGER ALL");
        jdbc.execute("ALTER TABLE approval_policy DROP CONSTRAINT approval_policy_hard_chk");
        try {
            jdbc.update("INSERT INTO approval_policy(tool_name, risk_level, requires_approval) VALUES ('pay', 'MONEY', FALSE)");
            assertThat(policies.requiresApproval("pay", RiskLevel.MONEY)).isTrue();
        } finally {
            jdbc.update("DELETE FROM approval_policy WHERE tool_name = 'pay'");
            jdbc.execute("ALTER TABLE approval_policy ADD CONSTRAINT approval_policy_hard_chk CHECK (risk_level NOT IN ('MONEY','DESTRUCTIVE') OR requires_approval)");
            jdbc.execute("ALTER TABLE approval_policy ENABLE TRIGGER ALL");
        }
    }

    @Test void perToolPolicyOverridesTheRiskDefaultAndIsAudited() {
        policies.put("send_email", RiskLevel.EXTERNAL, false, "owner");
        assertThat(policies.requiresApproval("send_email", RiskLevel.EXTERNAL)).isFalse();
        assertThat(policies.requiresApproval("other", RiskLevel.EXTERNAL)).isTrue();
        policies.put("send_email", RiskLevel.EXTERNAL, true, "owner");
        assertThat(policies.requiresApproval("send_email", RiskLevel.EXTERNAL)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permission_audit WHERE action='APPROVAL_POLICY_CHANGED'", Long.class)).isEqualTo(2);
    }

    @Test void databaseItselfRefusesToDisableMoney() {
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_policy SET requires_approval = FALSE WHERE risk_level = 'MONEY'"))
                .hasMessageContaining("approval_policy_hard_chk");
    }
}

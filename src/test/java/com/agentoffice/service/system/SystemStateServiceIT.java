package com.agentoffice.service.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.support.PlumbingIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SystemStateServiceIT extends PlumbingIT {
    @Autowired SystemStateService system;

    @Test void pauseAndResumeEmitEventsAndAuditOnce() {
        assertThat(system.isPaused()).isFalse();
        assertThat(system.pause("owner")).isTrue();
        assertThat(system.pause("owner")).as("idempotent").isFalse();
        assertThat(system.isPaused()).isTrue();
        assertThat(system.resume("owner")).isTrue();
        assertThat(system.isPaused()).isFalse();
        assertThat(eventTypes()).containsExactly("system.paused", "system.resumed");
        assertThat(jdbc.queryForList("SELECT action FROM permission_audit ORDER BY created_at", String.class))
                .containsExactly("SYSTEM_PAUSE", "SYSTEM_RESUME");
    }
}

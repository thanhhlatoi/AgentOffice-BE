package com.agentoffice.service.fe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NotifyServiceTest {
    private static JsonNode rules(String json) {
        try { return new ObjectMapper().readTree(json); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test void noRuleMeansAllowed() {
        assertThat(NotifyService.appAllowed(rules("[]"), "task.failed", "WARNING")).isTrue();
        assertThat(NotifyService.appAllowed(null, "task.failed", "WARNING")).isTrue();
    }

    @Test void appChannelOffBlocks() {
        JsonNode r = rules("[{\"type\":\"task.failed\",\"channels\":{\"app\":false},\"minLevel\":\"INFO\"}]");
        assertThat(NotifyService.appAllowed(r, "task.failed", "CRITICAL")).isFalse();
        assertThat(NotifyService.appAllowed(r, "goal.done", "SUCCESS")).isTrue();
    }

    @Test void minLevelFilters() {
        JsonNode r = rules("[{\"type\":\"task.failed\",\"channels\":{\"app\":true},\"minLevel\":\"CRITICAL\"}]");
        assertThat(NotifyService.appAllowed(r, "task.failed", "WARNING")).isFalse();
        assertThat(NotifyService.appAllowed(r, "task.failed", "CRITICAL")).isTrue();
    }

    @Test void scopedRulesAreIgnoredForGlobalFeed() {
        JsonNode r = rules("[{\"type\":\"task.failed\",\"channels\":{\"app\":false},\"minLevel\":\"INFO\",\"agentId\":\"a1\"}]");
        assertThat(NotifyService.appAllowed(r, "task.failed", "WARNING")).isTrue();
    }
}

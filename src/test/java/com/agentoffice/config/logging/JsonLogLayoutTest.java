package com.agentoffice.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonLogLayoutTest {
    @Test void oneValidJsonLineWithMdcAndEscaping() throws Exception {
        Logger logger = new LoggerContext().getLogger("x.Y");
        LoggingEvent e = new LoggingEvent("f", logger, Level.WARN, "xin \"chào\"\nxuống dòng {}", new IllegalStateException("boom"), new Object[] {"A"});
        e.setMDCPropertyMap(java.util.Map.of("requestId", "r-1", "taskId", "t-9"));
        String line = new JsonLogLayout().doLayout(e);
        assertThat(line).endsWith("\n").doesNotContain("\n{");
        JsonNode n = new ObjectMapper().readTree(line);
        assertThat(n.get("level").asText()).isEqualTo("WARN");
        assertThat(n.get("msg").asText()).isEqualTo("xin \"chào\"\nxuống dòng A");
        assertThat(n.get("requestId").asText()).isEqualTo("r-1");
        assertThat(n.get("taskId").asText()).isEqualTo("t-9");
        assertThat(n.get("stack").asText()).contains("IllegalStateException: boom");
    }
}

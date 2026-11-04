package com.agentoffice.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.config.web.CorrelationIdFilter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private String run(String header, AtomicReference<String> seenInChain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (header != null) req.addHeader("X-Request-Id", header);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain() {
            @Override public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                seenInChain.set(CorrelationIdFilter.current());
            }
        });
        assertThat(CorrelationIdFilter.current()).as("MDC phải được dọn sau request").isNull();
        return res.getHeader("X-Request-Id");
    }

    @Test void generatesAnIdAndExposesItInMdcAndHeader() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String header = run(null, seen);
        assertThat(header).isNotBlank().isEqualTo(seen.get());
    }

    @Test void reusesAValidIncomingId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        assertThat(run("abc-123_X.y", seen)).isEqualTo("abc-123_X.y");
        assertThat(seen.get()).isEqualTo("abc-123_X.y");
    }

    @Test void replacesAHostileOrOversizedId() throws Exception {
        for (String bad : new String[] {"a\nb", "x".repeat(65), "<script>", "a b"}) {
            AtomicReference<String> seen = new AtomicReference<>();
            assertThat(run(bad, seen)).as(bad).isNotEqualTo(bad).matches("[0-9a-f-]{36}");
        }
    }

    @Test void leavesNoMdcBehindEvenWhenTheChainThrows() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        try {
            filter.doFilter(new MockHttpServletRequest(), res, new MockFilterChain() {
                @Override public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) { throw new IllegalStateException("x"); }
            });
        } catch (Exception expected) { /* mong đợi */ }
        assertThat(MDC.get("requestId")).isNull();
    }
}

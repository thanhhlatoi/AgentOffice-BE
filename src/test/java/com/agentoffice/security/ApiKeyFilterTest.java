package com.agentoffice.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {
    private static final String KEY = "0123456789abcdef0123";

    private static boolean passes(ApiKeyFilter filter, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        boolean passed = chain.getRequest() != null;
        assertThat(res.getStatus()).isEqualTo(passed ? 200 : 401);
        return passed;
    }

    private static ApiKeyFilter filter() { return new ApiKeyFilter(true, KEY, new ObjectMapper()); }

    private static MockHttpServletRequest get(String path) { return new MockHttpServletRequest("GET", path); }

    @Test void thieuHoacSaiKhoaBiTuChoi() throws Exception {
        assertThat(passes(filter(), get("/api/hermes/bots"))).isFalse();
        MockHttpServletRequest wrong = get("/api/hermes/bots");
        wrong.addHeader(ApiKeyFilter.HEADER, "sai");
        assertThat(passes(filter(), wrong)).isFalse();
    }

    @Test void dungKhoaThiQua() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("DELETE", "/api/hermes/bots/x");
        req.addHeader(ApiKeyFilter.HEADER, KEY);
        assertThat(passes(filter(), req)).isTrue();
    }

    @Test void khoaTrenUrlChiDungDuocChoWebSocket() throws Exception {
        MockHttpServletRequest ws = get("/ws");
        ws.setParameter("access_token", KEY);
        assertThat(passes(filter(), ws)).isTrue();
        MockHttpServletRequest api = get("/api/fe/agents");
        api.setParameter("access_token", KEY);
        assertThat(passes(filter(), api)).isFalse();
    }

    @Test void preflightVaHealthDuocMien() throws Exception {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/fe/agents");
        preflight.addHeader("Origin", "http://localhost:5173");
        preflight.addHeader("Access-Control-Request-Method", "GET");
        assertThat(passes(filter(), preflight)).isTrue();
        assertThat(passes(filter(), get("/actuator/health"))).isTrue();
        assertThat(passes(filter(), get("/actuator/env"))).isFalse();
    }

    @Test void chuaDatKhoaHoacKhoaQuaNganThiTuChoiTatCa() throws Exception {
        for (String key : new String[] {"", "ngan"}) {
            MockHttpServletRequest req = get("/api/fe/agents");
            req.addHeader(ApiKeyFilter.HEADER, key);
            assertThat(passes(new ApiKeyFilter(true, key, new ObjectMapper()), req)).isFalse();
        }
    }

    @Test void tatBangCauHinhThiKhongKiemTra() throws Exception {
        assertThat(passes(new ApiKeyFilter(false, "", new ObjectMapper()), get("/api/fe/agents"))).isTrue();
    }
}

package com.agentoffice.domain.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Bỏ dòng này khi bắt đầu viết SsrfGuard")
class SsrfGuardTest {
    private final SsrfGuard g = new SsrfGuard();

    @Test void blocksLoopbackAndPrivate() {
        for (String u : new String[] {
                "http://127.0.0.1/", "http://localhost/", "http://10.0.0.5/", "http://192.168.1.1/",
                "http://172.16.0.1/", "http://169.254.169.254/latest/meta-data/", "http://[::1]/",
                "http://2130706433/", "http://0x7f.1/"}) {
            assertThatThrownBy(() -> g.assertAllowed(URI.create(u))).as(u).isInstanceOf(SecurityException.class);
        }
    }

    @Test void blocksNonHttpSchemes() {
        assertThatThrownBy(() -> g.assertAllowed(URI.create("file:///etc/passwd"))).isInstanceOf(SecurityException.class);
    }
}

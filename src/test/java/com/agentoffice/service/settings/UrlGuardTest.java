package com.agentoffice.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.exception.AppException;
import org.junit.jupiter.api.Test;

class UrlGuardTest {
    private final UrlGuard guard = new UrlGuard("");

    @Test void trustedNetworksAreAccepted() {
        for (String u : new String[] {"http://192.168.1.184:11434/v1", "http://10.0.0.5:8642", "http://172.16.3.4", "http://100.83.247.154:8642",
                "http://127.0.0.1:11434", "http://localhost:20128/v1", "http://[fd12:3456::1]:80"})
            assertThat(guard.check("f", u)).doesNotEndWith("/");
        assertThat(guard.check("f", "  http://192.168.1.184:11434/v1/  ")).isEqualTo("http://192.168.1.184:11434/v1");
    }

    @Test void metadataPublicAndMalformedAddressesAreBlocked() {
        for (String u : new String[] {"http://169.254.169.254/latest/meta-data", "http://[fe80::1]/", "http://0.0.0.0:80", "http://224.0.0.1",
                "http://8.8.8.8", "http://100.128.0.1", "ftp://192.168.1.1", "file:///etc/passwd", "http://user:pass@192.168.1.1/", "not a url", "", "http://"})
            assertThatThrownBy(() -> guard.check("f", u)).as(u).isInstanceOf(AppException.class);
    }

    @Test void publicHostsNeedToBeAllowListed() {
        assertThatThrownBy(() -> guard.check("f", "http://1.1.1.1/")).hasMessageContaining("AI_ALLOWED_HOSTS");
        assertThat(new UrlGuard("1.1.1.1, example.internal").check("f", "http://1.1.1.1/v1")).isEqualTo("http://1.1.1.1/v1");
        // allowlist không mở được link-local
        assertThatThrownBy(() -> new UrlGuard("169.254.169.254").check("f", "http://169.254.169.254/")).isInstanceOf(AppException.class);
    }
}

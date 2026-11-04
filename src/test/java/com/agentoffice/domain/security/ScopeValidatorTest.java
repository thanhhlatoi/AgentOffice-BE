package com.agentoffice.domain.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Bỏ dòng này khi bắt đầu viết ScopeValidator")
class ScopeValidatorTest {
    private final ScopeValidator v = new ScopeValidator();
    private final Path root = Path.of("/data/agent1");

    @Test void insidePathOk() {
        assertThat(v.resolveInside(root, "notes/a.txt")).isEqualTo(root.resolve("notes/a.txt"));
    }

    @Test void traversalRejected() {
        assertThatThrownBy(() -> v.resolveInside(root, "../agent2/secret.txt")).isInstanceOf(SecurityException.class);
    }

    @Test void siblingPrefixRejected() {
        // /data/agent10 bắt đầu bằng "/data/agent1" nhưng KHÔNG nằm trong root
        assertThatThrownBy(() -> v.resolveInside(root, "../agent10/x")).isInstanceOf(SecurityException.class);
    }

    @Test void hostMatchingIsExactOrSubdomain() {
        List<String> allowed = List.of("example.com");
        assertThat(v.hostAllowed("example.com", allowed)).isTrue();
        assertThat(v.hostAllowed("api.example.com", allowed)).isTrue();
        assertThat(v.hostAllowed("evil-example.com", allowed)).isFalse();
        assertThat(v.hostAllowed("example.com.evil.io", allowed)).isFalse();
    }
}

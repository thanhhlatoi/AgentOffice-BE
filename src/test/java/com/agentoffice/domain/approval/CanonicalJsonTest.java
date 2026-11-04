package com.agentoffice.domain.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test void keyOrderAndWhitespaceDoNotMatter() {
        assertThat(CanonicalJson.canonicalize("{ \"b\": 1,\n \"a\": {\"y\":2, \"x\":[3,{\"q\":1,\"p\":2}]} }"))
                .isEqualTo("{\"a\":{\"x\":[3,{\"p\":2,\"q\":1}],\"y\":2},\"b\":1}");
    }

    @Test void arrayOrderMatters() {
        assertThat(CanonicalJson.canonicalize("[1,2]")).isNotEqualTo(CanonicalJson.canonicalize("[2,1]"));
    }

    @Test void keyDependsOnTaskToolAndArgs() {
        UUID t = UUID.randomUUID();
        String k = CanonicalJson.idempotencyKey(t, "send", "{}");
        assertThat(k).hasSize(64).isEqualTo(CanonicalJson.idempotencyKey(t, "send", "{}"));
        assertThat(k).isNotEqualTo(CanonicalJson.idempotencyKey(UUID.randomUUID(), "send", "{}"));
        assertThat(k).isNotEqualTo(CanonicalJson.idempotencyKey(t, "other", "{}"));
        assertThat(k).isNotEqualTo(CanonicalJson.idempotencyKey(t, "send", "{\"a\":1}"));
    }

    @Test void invalidJsonIsRejected() {
        assertThatThrownBy(() -> CanonicalJson.canonicalize("{oops")).isInstanceOf(IllegalArgumentException.class);
    }
}

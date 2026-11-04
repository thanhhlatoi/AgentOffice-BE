package com.agentoffice.domain.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Bỏ dòng này khi bắt đầu viết SecretRedactor")
class SecretRedactorTest {
    private final SecretRedactor r = new SecretRedactor();

    @Test void masksBearerToken() {
        assertThat(r.redact("Authorization: Bearer abc.def.ghi")).doesNotContain("abc.def.ghi").contains("[REDACTED]");
    }

    @Test void masksApiKeyAssignment() {
        assertThat(r.redact("api_key=sk-1234567890abcdef")).doesNotContain("1234567890abcdef");
    }

    @Test void masksCardNumber() {
        assertThat(r.redact("thẻ 4111 1111 1111 1111 nhé")).doesNotContain("4111 1111 1111 1111");
    }

    @Test void leavesNormalVietnameseTextAlone() {
        String s = "Hôm nay họp lúc 9 giờ, phòng số 12.";
        assertThat(r.redact(s)).isEqualTo(s);
    }
}

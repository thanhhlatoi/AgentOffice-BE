package com.agentoffice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class OwnerAuthTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test void correctBearerTokenPasses() {
        assertThatCode(() -> new OwnerAuth(TOKEN).require("Bearer " + TOKEN)).doesNotThrowAnyException();
    }

    @Test void wrongMissingOrMalformedTokensAreRejected() {
        OwnerAuth a = new OwnerAuth(TOKEN);
        for (String h : new String[] {null, "", "Bearer ", "Bearer wrong", TOKEN, "Basic " + TOKEN, "Bearer " + TOKEN + "x"})
            assertThatThrownBy(() -> a.require(h)).isInstanceOf(AppException.class).extracting(e -> ((AppException) e).code()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test void disabledWhenTokenUnsetOrTooShort() {
        assertThat(new OwnerAuth("").enabled()).isFalse();
        assertThat(new OwnerAuth("short").enabled()).isFalse();
        assertThatThrownBy(() -> new OwnerAuth("").require("Bearer ")).isInstanceOf(AppException.class);   // chưa đặt mã thì không ai sửa được, kể cả gửi mã rỗng
    }
}

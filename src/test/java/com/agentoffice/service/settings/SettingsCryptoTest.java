package com.agentoffice.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.exception.AppException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SettingsCryptoTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
    private final SettingsCrypto crypto = new SettingsCrypto(KEY);

    @Test void roundTripAndNeverPlaintext() {
        String sealed = crypto.seal("llm.apiKey", "sk-SECRET");
        assertThat(sealed).startsWith("v1:").doesNotContain("sk-SECRET");
        assertThat(crypto.open("llm.apiKey", sealed)).isEqualTo("sk-SECRET");
    }

    @Test void everySealUsesAFreshIv() {
        assertThat(crypto.seal("k", "same")).isNotEqualTo(crypto.seal("k", "same"));
    }

    @Test void ciphertextIsBoundToItsSettingName() {
        String sealed = crypto.seal("llm.apiKey", "sk-SECRET");
        assertThatThrownBy(() -> crypto.open("omniroute.apiKey", sealed)).isInstanceOf(AppException.class);
    }

    @Test void tamperedOrWrongKeyFails() {
        String sealed = crypto.seal("k", "x");
        assertThatThrownBy(() -> crypto.open("k", sealed.substring(0, sealed.length() - 2) + "AA")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> new SettingsCrypto(OTHER_KEY).open("k", sealed)).isInstanceOf(AppException.class);
    }

    @Test void notReadyWithoutAValid32ByteKey() {
        for (String k : new String[] {"", "  ", "not-base64!!", Base64.getEncoder().encodeToString(new byte[16])}) {
            SettingsCrypto c = new SettingsCrypto(k);
            assertThat(c.ready()).as(k).isFalse();
            assertThatThrownBy(() -> c.seal("k", "x")).isInstanceOf(AppException.class).hasMessageContaining("AGENTOFFICE_MASTER_KEY");
        }
    }
}

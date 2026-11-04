package com.agentoffice.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {
    @Test void removesVietnameseAccents() {
        assertThat(TextNormalizer.forSearch("Hội thoại Đặng")).isEqualTo("hoi thoai dang");
    }
    @Test void nullIsEmpty() {
        assertThat(TextNormalizer.forSearch(null)).isEmpty();
    }
}

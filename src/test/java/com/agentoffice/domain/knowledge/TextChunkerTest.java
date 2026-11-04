package com.agentoffice.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Bỏ dòng này khi bắt đầu viết TextChunker")
class TextChunkerTest {
    private final TextChunker chunker = new TextChunker();

    @Test void noChunkExceedsMax() {
        String text = "Câu một. ".repeat(200);
        assertThat(chunker.chunk(text, 200, 20)).allMatch(c -> c.length() <= 200);
    }

    @Test void shortTextIsOneChunk() {
        assertThat(chunker.chunk("Xin chào", 200, 20)).containsExactly("Xin chào");
    }

    @Test void doesNotCutInsideWord() {
        String text = "alpha beta gamma delta epsilon zeta eta theta";
        assertThat(chunker.chunk(text, 12, 0)).allMatch(c -> !c.startsWith(" ") && !c.endsWith(" "));
    }
}

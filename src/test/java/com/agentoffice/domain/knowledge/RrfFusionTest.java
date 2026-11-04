package com.agentoffice.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Bỏ dòng này khi bắt đầu viết RrfFusion")
class RrfFusionTest {
    private final RrfFusion rrf = new RrfFusion();
    private final UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3);

    @Test void itemInBothListsWins() {
        // a: hạng 1 ở list1, hạng 2 ở list2; b: chỉ hạng 1 ở list2
        List<UUID> out = rrf.fuse(List.of(List.of(a, c), List.of(b, a)), 60, 3);
        assertThat(out.get(0)).isEqualTo(a);
    }

    @Test void respectsTopN() {
        assertThat(rrf.fuse(List.of(List.of(a, b, c)), 60, 2)).hasSize(2);
    }

    @Test void emptyInputEmptyOutput() {
        assertThat(rrf.fuse(List.of(), 60, 5)).isEmpty();
    }
}

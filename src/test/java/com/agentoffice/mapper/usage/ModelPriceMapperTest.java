package com.agentoffice.mapper.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.dto.usage.ModelPriceDto;
import com.agentoffice.entity.usage.ModelPrice;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelPriceMapperTest {
    private final ModelPriceMapper mapper = new ModelPriceMapperImpl();     // MapStruct sinh lúc biên dịch

    @Test void mapsRenamedFieldsAndTheLocalFlag() {
        ModelPrice p = new ModelPrice("anthropic", "claude-haiku-5-5", 1_000_000, 5_000_000, 100_000L,
                Instant.parse("2026-01-01T00:00:00Z"), true, "mẫu");
        ModelPriceDto d = mapper.toDto(p);
        assertThat(d.provider()).isEqualTo("anthropic");
        assertThat(d.inputPerMTokMicroUsd()).isEqualTo(1_000_000);
        assertThat(d.outputPerMTokMicroUsd()).isEqualTo(5_000_000);
        assertThat(d.cachedInputPerMTokMicroUsd()).isEqualTo(100_000L);
        assertThat(d.effectiveFrom()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(d.isLocal()).isTrue();
        assertThat(d.note()).isEqualTo("mẫu");
        assertThat(mapper.toDtos(List.of(p, p))).hasSize(2);
        assertThat(mapper.toDto(null)).isNull();
    }
}

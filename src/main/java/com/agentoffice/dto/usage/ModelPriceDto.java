package com.agentoffice.dto.usage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Giá tính bằng micro-USD cho 1 triệu token (số nguyên). */
public record ModelPriceDto(UUID id, String provider, String model, long inputPerMTokMicroUsd, long outputPerMTokMicroUsd,
                            Long cachedInputPerMTokMicroUsd, Instant effectiveFrom, Instant effectiveTo, boolean isLocal, String note) {

    public record Put(@NotBlank @Size(max = 50) String provider, @NotBlank @Size(max = 100) String model,
                      @NotNull @PositiveOrZero Long inputPerMTokMicroUsd, @NotNull @PositiveOrZero Long outputPerMTokMicroUsd,
                      @PositiveOrZero Long cachedInputPerMTokMicroUsd, @NotNull Instant effectiveFrom,
                      boolean isLocal, @Size(max = 200) String note) {}
}

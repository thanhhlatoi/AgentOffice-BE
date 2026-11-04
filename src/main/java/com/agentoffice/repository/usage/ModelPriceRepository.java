package com.agentoffice.repository.usage;

import com.agentoffice.entity.usage.ModelPrice;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelPriceRepository extends JpaRepository<ModelPrice, UUID> {
    /** Bản ghi có effectiveFrom muộn nhất mà <= at; người gọi phải kiểm effectiveTo (có thể là khoảng trống giữa 2 bản). */
    Optional<ModelPrice> findFirstByProviderAndModelAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(String provider, String model, Instant at);

    Optional<ModelPrice> findFirstByProviderAndModelOrderByEffectiveFromDesc(String provider, String model);

    List<ModelPrice> findAllByOrderByProviderAscModelAscEffectiveFromDesc();

    List<ModelPrice> findByProviderAndModelOrderByEffectiveFromDesc(String provider, String model);
}

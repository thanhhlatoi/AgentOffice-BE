package com.agentoffice.service.usage;

import com.agentoffice.dto.usage.ModelPriceDto;
import com.agentoffice.entity.usage.ModelPrice;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.repository.usage.ModelPriceRepository;
import com.agentoffice.service.audit.PermissionAuditLog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceService {
    public record Resolved(String provider, String model) {}

    private final ModelPriceRepository prices;
    private final JdbcTemplate jdbc;
    private final PermissionAuditLog audit;

    public PriceService(ModelPriceRepository prices, JdbcTemplate jdbc, PermissionAuditLog audit) {
        this.prices = prices;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** `requested` có thể là tên model_profile (vd "cloud.mid") hoặc tên model thô; không khớp profile thì dùng provider của client. */
    @Transactional(readOnly = true)
    public Resolved resolve(String requested, String fallbackProvider) {
        List<Resolved> hit = jdbc.query("SELECT provider, model FROM model_profile WHERE name = ?",
                (rs, i) -> new Resolved(rs.getString(1), rs.getString(2)), requested);
        return hit.isEmpty() ? new Resolved(fallbackProvider, requested) : hit.get(0);
    }

    /** Giá có hiệu lực tại `at`; rỗng nếu chưa có (hoặc rơi vào khoảng trống giữa hai bản). */
    @Transactional(readOnly = true)
    public Optional<ModelPrice> priceAt(String provider, String model, Instant at) {
        return prices.findFirstByProviderAndModelAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(provider, model, at)
                .filter(p -> p.getEffectiveTo() == null || at.isBefore(p.getEffectiveTo()));
    }

    @Transactional(readOnly = true)
    public List<ModelPrice> list(String provider, String model) {
        return provider != null && model != null ? prices.findByProviderAndModelOrderByEffectiveFromDesc(provider, model)
                : prices.findAllByOrderByProviderAscModelAscEffectiveFromDesc();
    }

    /** Thêm bản ghi giá mới; đóng bản đang mở của cùng (provider, model). effectiveFrom phải SAU mọi bản đã có, nếu không 409. */
    @Transactional
    public ModelPrice add(ModelPriceDto.Put r, String actor) {
        Optional<ModelPrice> latest = prices.findFirstByProviderAndModelOrderByEffectiveFromDesc(r.provider(), r.model());
        if (latest.isPresent() && !r.effectiveFrom().isAfter(latest.get().getEffectiveFrom()))
            throw new AppException(ErrorCode.CONFLICT, "Giá cũ không được sửa: đã có bản ghi hiệu lực từ "
                    + latest.get().getEffectiveFrom() + ", bản mới phải có effectiveFrom muộn hơn");
        try {
            latest.filter(p -> p.getEffectiveTo() == null).ifPresent(p -> { p.closeAt(r.effectiveFrom()); prices.saveAndFlush(p); });
            ModelPrice saved = prices.saveAndFlush(new ModelPrice(r.provider(), r.model(), r.inputPerMTokMicroUsd(),
                    r.outputPerMTokMicroUsd(), r.cachedInputPerMTokMicroUsd(), r.effectiveFrom(), r.isLocal(), r.note()));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("provider", r.provider());
            d.put("model", r.model());
            d.put("input", r.inputPerMTokMicroUsd());
            d.put("output", r.outputPerMTokMicroUsd());
            d.put("effectiveFrom", r.effectiveFrom().toString());
            audit.record(actor, "MODEL_PRICE_ADD", d);
            return saved;
        } catch (DataIntegrityViolationException e) {   // đua nhau thêm cùng lúc => ràng buộc không-chồng chặn
            throw new AppException(ErrorCode.CONFLICT, "Khoảng hiệu lực giá bị chồng, hãy tải lại", null, e);
        }
    }
}

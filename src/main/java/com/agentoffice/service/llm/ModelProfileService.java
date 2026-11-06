package com.agentoffice.service.llm;

import com.agentoffice.dto.llm.ModelProfileDto;
import com.agentoffice.service.usage.PriceService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Danh sách model_profile kèm trạng thái: có trên gateway không, có giá hiệu lực không. */
@Service
public class ModelProfileService {
    private static final Logger log = LoggerFactory.getLogger(ModelProfileService.class);
    private static final String SQL = "SELECT p.name, p.provider, p.model, p.is_local, e.name FROM model_profile p "
            + "LEFT JOIN model_profile e ON e.id = p.escalate_to ORDER BY p.name";

    private final JdbcTemplate jdbc;
    private final PriceService prices;
    private final LlmModelService gateway;
    private final Clock clock;

    public ModelProfileService(JdbcTemplate jdbc, PriceService prices, LlmModelService gateway, Clock clock) {
        this.jdbc = jdbc;
        this.prices = prices;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ModelProfileDto> list() {
        Set<String> onGateway = gatewayIds();
        Instant now = clock.instant();
        return jdbc.query(SQL, (rs, i) -> {
            String provider = rs.getString(2), model = rs.getString(3);
            var price = prices.priceAt(provider, model, now);
            return new ModelProfileDto(rs.getString(1), provider, model, rs.getBoolean(4), rs.getString(5),
                    onGateway == null ? null : onGateway.contains(rs.getString(1)),   // tên profile là chuỗi được gửi làm model
                    price.isPresent(), price.map(p -> p.getEffectiveFrom()).orElse(null));
        });
    }

    /** null = không xác định (fake hoặc gateway lỗi): trang danh sách vẫn phải hiện được nên chỉ log, không ném. */
    private Set<String> gatewayIds() {
        try {
            Set<String> ids = gateway.modelIds();
            return ids.isEmpty() ? null : ids;
        } catch (RuntimeException e) {
            log.warn("Không đối chiếu được với gateway: {}", e.toString());
            return null;
        }
    }
}

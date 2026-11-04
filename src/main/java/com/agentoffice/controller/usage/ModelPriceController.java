package com.agentoffice.controller.usage;

import com.agentoffice.dto.usage.ModelPriceDto;
import com.agentoffice.mapper.usage.ModelPriceMapper;
import com.agentoffice.security.SensitiveAction;
import com.agentoffice.service.usage.PriceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/model-prices")
public class ModelPriceController {
    private final PriceService prices;
    private final ModelPriceMapper mapper;

    public ModelPriceController(PriceService prices, ModelPriceMapper mapper) {
        this.prices = prices;
        this.mapper = mapper;
    }

    /** Lịch sử giá (mới nhất trước trong từng model). Lọc theo provider+model nếu truyền cả hai. */
    @GetMapping
    public List<ModelPriceDto> list(@RequestParam(required = false) String provider, @RequestParam(required = false) String model) {
        return mapper.toDtos(prices.list(provider, model));
    }

    /** Thêm bản ghi giá mới có hiệu lực từ effectiveFrom. Không sửa được bản cũ: effectiveFrom không muộn hơn bản mới nhất => 409. */
    @PutMapping
    @SensitiveAction("model-price.add")
    public ModelPriceDto put(@Valid @RequestBody ModelPriceDto.Put r) {
        return mapper.toDto(prices.add(r, "owner"));
    }
}

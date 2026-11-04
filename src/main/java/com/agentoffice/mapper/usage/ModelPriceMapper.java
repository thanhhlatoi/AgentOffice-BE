package com.agentoffice.mapper.usage;

import com.agentoffice.dto.usage.ModelPriceDto;
import com.agentoffice.entity.usage.ModelPrice;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Entity -> DTO. Tên cột DB (…MicroPerMtok) khác tên JSON (…PerMTokMicroUsd) nên khai báo rõ; thiếu trường => lỗi biên dịch. */
@Mapper
public interface ModelPriceMapper {
    @Mapping(target = "inputPerMTokMicroUsd", source = "inputMicroPerMtok")
    @Mapping(target = "outputPerMTokMicroUsd", source = "outputMicroPerMtok")
    @Mapping(target = "cachedInputPerMTokMicroUsd", source = "cachedInputMicroPerMtok")
    @Mapping(target = "isLocal", source = "local")
    ModelPriceDto toDto(ModelPrice p);

    List<ModelPriceDto> toDtos(List<ModelPrice> prices);
}

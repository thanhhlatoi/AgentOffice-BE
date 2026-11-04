package com.agentoffice.dto.common;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageDto<T>(List<T> items, int page, int size, long total) {
    public static <T> PageDto<T> of(Page<T> p) {
        return new PageDto<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements());
    }
}

package com.agentoffice.service.llm;

import com.agentoffice.client.llm.LlmClient;
import com.agentoffice.client.llm.LlmModel;
import com.agentoffice.domain.usage.LlmCallException;
import com.agentoffice.dto.llm.LlmModelDto;
import com.agentoffice.dto.llm.LlmProviderDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.agentoffice.service.redis.RedisCoordinator;
import java.time.Duration;

/** Liệt kê nhà cung cấp/model của gateway. Không tốn token nên đi thẳng LlmClient thô (không qua MeteredLlmClient); provider=fake => rỗng. */
@Service
public class LlmModelService {
    static final String UNKNOWN = "unknown";
    private final LlmClient client;
    private final RedisCoordinator redis;

    @Autowired
    public LlmModelService(@Qualifier("raw") LlmClient client, RedisCoordinator redis) { this.client = client; this.redis = redis; }

    LlmModelService(LlmClient client) { this(client, null); }

    /** Các nhà cung cấp đã kết nối (theo thứ tự gateway trả về) kèm số model. */
    public List<LlmProviderDto> providers() {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (LlmModel m : fetch()) count.merge(providerOf(m), 1, Integer::sum);
        return count.entrySet().stream().map(e -> new LlmProviderDto(e.getKey(), e.getValue())).toList();
    }

    /** Model của đúng một nhà cung cấp. */
    public List<LlmModelDto> models(String provider) {
        return fetch().stream().filter(m -> providerOf(m).equals(provider)).map(m -> new LlmModelDto(m.id(), m.ownedBy(), m.contextWindow(), m.maxOutput(), m.vision(), m.tools(), m.reasoning())).toList();
    }

    /** Mọi model id gateway đang phục vụ (rỗng khi fake). */
    /** Dùng cho kiểm tra kết nối chủ động: luôn hỏi gateway thật, không ghi trạng thái health từ cache cũ. */
    public Set<String> modelIds() { return fetchOrigin().stream().map(LlmModel::id).collect(Collectors.toSet()); }

    private static String providerOf(LlmModel m) { return m.ownedBy() == null || m.ownedBy().isBlank() ? UNKNOWN : m.ownedBy(); }

    private List<LlmModel> fetch() {
        if (redis != null) return redis.cachedList("gateway-models", LlmModel.class, Duration.ofSeconds(45), this::fetchOrigin);
        return fetchOrigin();
    }

    private List<LlmModel> fetchOrigin() {
        try {
            return client.models();
        } catch (LlmCallException e) {
            throw new AppException(ErrorCode.LLM_GATEWAY_ERROR, "Không lấy được danh sách model từ gateway: " + e.getMessage(), null, e);
        }
    }
}

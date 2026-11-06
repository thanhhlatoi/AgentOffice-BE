package com.agentoffice.client.llm;

import com.agentoffice.config.RuntimeAiConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * LlmClient "thô" duy nhất mà MeteredLlmClient thấy: chọn AI giả hoặc AI local THEO CẤU HÌNH ĐANG CHẠY ở mỗi cuộc gọi,
 * nên đổi "Chế độ AI" trong Cài đặt AI có hiệu lực ngay, không cần khởi động lại BE.
 */
@Component
@Qualifier("raw")
public class SwitchingLlmClient implements LlmClient {
    private final RuntimeAiConfig cfg;
    private final FakeLlmClient fake;
    private final OpenAiCompatLlmClient local;

    public SwitchingLlmClient(RuntimeAiConfig cfg, FakeLlmClient fake, OpenAiCompatLlmClient local) {
        this.cfg = cfg;
        this.fake = fake;
        this.local = local;
    }

    private LlmClient current() { return cfg.get().local() ? local : fake; }

    @Override
    public String provider() { return current().provider(); }

    @Override
    public LlmResponse complete(LlmRequest req) { return current().complete(req); }
}

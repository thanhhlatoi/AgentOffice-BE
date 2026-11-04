package com.agentoffice.client.llm;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Trả lời giả, để chạy được toàn bộ luồng mà chưa tốn tiền token. */
@Component
@Qualifier("raw")
@ConditionalOnProperty(name = "agentoffice.llm.provider", havingValue = "fake", matchIfMissing = true)
public class FakeLlmClient implements LlmClient {
    @Override public String provider() { return "fake"; }

    @Override
    public LlmResponse complete(LlmRequest req) {
        String last = req.messages().isEmpty() ? "" : req.messages().get(req.messages().size() - 1).content();
        String text = "[fake] " + last;
        return new LlmResponse(text, last.length() / 4 + 1, text.length() / 4 + 1, req.model());
    }
}

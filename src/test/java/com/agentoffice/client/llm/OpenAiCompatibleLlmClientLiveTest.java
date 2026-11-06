package com.agentoffice.client.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.agentoffice.config.LlmProperties;
import com.agentoffice.domain.usage.CallContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Gọi 9Router THẬT (tốn một ít token). Bỏ qua nếu thiếu NINEROUTER_API_KEY / NINEROUTER_MODEL. NINEROUTER_URL mặc định localhost:20128. */
class OpenAiCompatibleLlmClientLiveTest {
    @Test void realGatewayAnswers() {
        String key = System.getenv("NINEROUTER_API_KEY"), model = System.getenv("NINEROUTER_MODEL");
        assumeTrue(key != null && !key.isBlank() && model != null && !model.isBlank(), "đặt NINEROUTER_API_KEY và NINEROUTER_MODEL để chạy");
        String url = System.getenv().getOrDefault("NINEROUTER_URL", "http://localhost:20128");
        var client = new OpenAiCompatibleLlmClient(new LlmProperties("openai-compatible",
                new LlmProperties.Gateway(url, key, Duration.ofSeconds(5), Duration.ofSeconds(60))), new ObjectMapper());

        LlmResponse r = client.complete(new LlmRequest(model, List.of(new LlmRequest.Msg("user", "Trả lời đúng một từ: pong")), 256,
                new CallContext(null, null, null, null, null, CallContext.Purpose.MODEL_TEST, null)));

        System.out.println("LIVE model=" + r.model() + " in=" + r.inputTokens() + " out=" + r.outputTokens() + " text=" + r.text());
        assertThat(r.text()).isNotBlank();
    }

    @Test void realGatewayListsModels() {
        String key = System.getenv("NINEROUTER_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "đặt NINEROUTER_API_KEY để chạy");
        var client = new OpenAiCompatibleLlmClient(new LlmProperties("openai-compatible", new LlmProperties.Gateway(
                System.getenv().getOrDefault("NINEROUTER_URL", "http://localhost:20128"), key, Duration.ofSeconds(5), Duration.ofSeconds(30))), new ObjectMapper());

        List<LlmModel> models = client.models();

        System.out.println("LIVE models=" + models.size() + " first=" + models.get(0) + " withContext=" + models.stream().filter(m -> m.contextWindow() != null).count() + " vision=" + models.stream().filter(m -> Boolean.TRUE.equals(m.vision())).count());
        assertThat(models).isNotEmpty();
    }
}

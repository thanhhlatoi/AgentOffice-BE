package com.agentoffice.client.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.config.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Chọn provider bằng cấu hình thật (bind + @ConditionalOnProperty), không dựng toàn bộ app. */
class LlmProviderWiringTest {
    @Configuration
    @EnableConfigurationProperties(LlmProperties.class)
    @Import({FakeLlmClient.class, OpenAiCompatibleLlmClient.class})
    static class Beans {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Beans.class);

    @Test void defaultIsFake() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(FakeLlmClient.class).doesNotHaveBean(OpenAiCompatibleLlmClient.class));
    }

    @Test void openAiCompatibleAddsGatewayBean() {   // FakeLlmClient luôn có bean (SwitchingLlmClient cần); gateway thắng ở SwitchingLlmClient
        runner.withPropertyValues("agentoffice.llm.provider=openai-compatible", "agentoffice.llm.gateway.base-url=http://localhost:20128")
                .run(ctx -> assertThat(ctx).hasSingleBean(OpenAiCompatibleLlmClient.class));
    }

    @Test void missingBaseUrlStopsStartup() {   // NINEROUTER_URL trống => ${NINEROUTER_URL:} = ""
        runner.withPropertyValues("agentoffice.llm.provider=openai-compatible", "agentoffice.llm.gateway.base-url=")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining("NINEROUTER_URL"));
    }

    @Test void bindsTimeoutsFromYamlStyleValues() {
        runner.withPropertyValues("agentoffice.llm.gateway.read-timeout=30s")
                .run(ctx -> assertThat(ctx.getBean(LlmProperties.class).gateway().readTimeout().toSeconds()).isEqualTo(30));
    }
}

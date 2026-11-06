package com.agentoffice.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cấu hình AI ĐANG CHẠY: giá trị mặc định lấy từ .env/application.yml, ghi đè bằng bảng ai_setting (chỉnh từ giao diện).
 * Mọi client (LLM, Planner, Hermes) đọc get() ở MỖI lần gọi nên đổi cấu hình có hiệu lực ngay, không cần khởi động lại.
 * get() trả ảnh chụp bất biến: một cuộc gọi dùng nhất quán một bộ giá trị dù cấu hình đổi giữa chừng.
 * Giá trị khoá nằm trong ảnh chụp (bộ nhớ) để gọi API, KHÔNG bao giờ được ghi log hay trả ra ngoài.
 */
@Component
public class RuntimeAiConfig {
    // Khoá trong bảng ai_setting (trùng "nhóm.trường" của PUT /settings/ai)
    public static final String PROVIDER = "llm.provider", REASONING = "llm.reasoningEffort", TIMEOUT = "llm.timeoutSec",
            MAX_OUTPUT = "llm.maxOutputTokens", VIA_OMNI = "llm.viaOmniroute", OLLAMA_URL = "ollama.baseUrl",
            MODEL = "ollama.defaultModel", OMNI_URL = "omniroute.baseUrl", ALIASES = "llm.modelAliases",
            HERMES_ENABLED = "hermes.enabled", HERMES_PLANNER = "hermes.planner", HERMES_URL = "hermes.baseUrl";
    public static final String SECRET_LLM = "llm.apiKey", SECRET_OMNI = "omniroute.apiKey", SECRET_ADMIN = "hermes.adminToken";
    public static final String DEFAULT_OMNI_URL = "http://localhost:20128/v1";

    /** ảnh chụp cấu hình. plannerMode: fake | llm | hermes. */
    public record Effective(String provider, String providerName, String ollamaBaseUrl, String ollamaApiKey, boolean viaOmniroute,
                            String omniBaseUrl, String omniApiKey, String model, Map<String, String> aliases, String reasoningEffort,
                            Duration timeout, int maxOutputTokens, boolean hermesEnabled, String hermesPlanner, String hermesBaseUrl,
                            String hermesAdminKey, String plannerMode) {
        /** Địa chỉ + khoá của đường gọi chat thật: OmniRoute nếu bật, không thì Ollama. */
        public String chatBaseUrl() { return viaOmniroute ? omniBaseUrl : ollamaBaseUrl; }

        public String chatApiKey() { return viaOmniroute ? omniApiKey : ollamaApiKey; }

        public boolean local() { return "local".equals(provider); }
    }

    private final LocalLlmProperties local;
    private final HermesProperties hermes;
    private final HermesAdminProperties admin;
    private final String envProvider;
    private final String envPlanner;
    private volatile Effective current;
    private volatile Set<String> secretKeysSet = Set.of();

    public RuntimeAiConfig(LocalLlmProperties local, HermesProperties hermes, HermesAdminProperties admin,
                           @Value("${agentoffice.llm.provider:fake}") String provider, @Value("${agentoffice.planner:fake}") String planner) {
        this.local = local;
        this.hermes = hermes;
        this.admin = admin;
        this.envProvider = "local".equals(provider) ? "local" : "fake";
        this.envPlanner = switch (planner) { case "hermes", "llm" -> planner; default -> "fake"; };
        this.current = build(Map.of(), Map.of());
    }

    public Effective get() { return current; }

    /** Tên các khoá bí mật đang được đặt trong DB (để báo apiKeySet; không có giá trị). */
    public boolean secretSet(String key) { return secretKeysSet.contains(key); }

    /** Dựng lại từ mặc định .env + giá trị ghi đè. Gọi sau khi nạp DB hoặc sau khi lưu. */
    public void apply(Map<String, String> overrides, Map<String, String> secrets) {
        this.current = build(overrides, secrets);
        this.secretKeysSet = Set.copyOf(secrets.keySet());
    }

    private Effective build(Map<String, String> o, Map<String, String> s) {
        String provider = o.getOrDefault(PROVIDER, envProvider);
        String ollamaUrl = o.getOrDefault(OLLAMA_URL, local.baseUrl().trim());
        boolean via = Boolean.parseBoolean(o.getOrDefault(VIA_OMNI, "false"));
        String model = o.getOrDefault(MODEL, local.defaultModel());
        Map<String, String> aliases = o.containsKey(ALIASES) ? parseAliases(o.get(ALIASES)) : new LinkedHashMap<>();
        if (!o.containsKey(ALIASES) && local.modelAliases() != null) {
            // bí danh lấy từ .env mà đang trỏ về model mặc định cũ thì theo model mặc định mới, nếu không đổi "Model mặc định" sẽ không có tác dụng
            local.modelAliases().forEach((k, v) -> aliases.put(k, v.equals(local.defaultModel()) ? model : v));
        }
        boolean hermesDefault = "hermes".equals(envPlanner);
        boolean hermesEnabled = Boolean.parseBoolean(o.getOrDefault(HERMES_ENABLED, String.valueOf(hermesDefault)));
        String hermesPlanner = o.getOrDefault(HERMES_PLANNER, hermesDefault ? "hermes" : "llm");
        boolean plannerTouched = o.containsKey(HERMES_ENABLED) || o.containsKey(HERMES_PLANNER);
        String mode = plannerTouched
                ? (hermesEnabled && "hermes".equals(hermesPlanner) ? "hermes" : "local".equals(provider) ? "llm" : "fake")
                : envPlanner;
        return new Effective(provider, local.providerName(), ollamaUrl, s.getOrDefault(SECRET_LLM, local.apiKey().trim()), via,
                o.getOrDefault(OMNI_URL, DEFAULT_OMNI_URL), s.getOrDefault(SECRET_OMNI, ""),
                model, Map.copyOf(aliases), o.getOrDefault(REASONING, local.reasoningEffort().trim()),
                Duration.ofSeconds(Long.parseLong(o.getOrDefault(TIMEOUT, String.valueOf(local.timeout().toSeconds())))),
                Integer.parseInt(o.getOrDefault(MAX_OUTPUT, "2000")), hermesEnabled, hermesPlanner,
                o.getOrDefault(HERMES_URL, hermes.baseUrl().trim()), s.getOrDefault(SECRET_ADMIN, admin.key().trim()), mode);
    }

    private static Map<String, String> parseAliases(String json) {
        try {
            Map<String, String> m = new LinkedHashMap<>();
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
            return m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** Cho test và nơi dựng thủ công: cấu hình cố định từ các Properties, không DB. */
    public static RuntimeAiConfig of(LocalLlmProperties local, HermesProperties hermes, HermesAdminProperties admin, String provider, String planner) {
        return new RuntimeAiConfig(local, hermes, admin, provider, planner);
    }
}

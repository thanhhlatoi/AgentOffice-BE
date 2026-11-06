package com.agentoffice.service.settings;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.client.llm.OllamaClient;
import com.agentoffice.config.HermesProperties;
import com.agentoffice.config.RuntimeAiConfig;
import com.agentoffice.config.web.CorrelationIdFilter;
import com.agentoffice.dto.fe.SettingsAiDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.security.OwnerAuth;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cài đặt AI: đọc cấu hình đang chạy, kiểm tra tình trạng Ollama/Hermes, liệt kê model thật, thử gọi, và SỬA từ giao diện.
 * Sửa = ghi vào bảng ai_setting (khoá bí mật mã hoá) rồi nạp lại RuntimeAiConfig nên có hiệu lực ngay. Việc sửa chỉ mở khi
 * có OWNER_TOKEN (kiểm ở controller). Không bao giờ trả giá trị khoá; mọi địa chỉ phải qua UrlGuard; /test chỉ thử đúng địa chỉ đã lưu.
 */
@Service
public class SettingsAiService {
    private static final Logger log = LoggerFactory.getLogger(SettingsAiService.class);
    private static final long SLOW_MS = 2000;
    private static final Pattern ALIAS_KEY = Pattern.compile("^[\\w.-]{1,60}$");
    private static final Set<String> EFFORT = Set.of("", "none", "low", "medium", "high");
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "llm", Set.of("provider", "reasoningEffort", "timeoutSec", "maxOutputTokens", "viaOmniroute", "modelAliases", "apiKey"),
            "ollama", Set.of("baseUrl", "defaultModel"),
            "omniroute", Set.of("enabled", "baseUrl", "apiKey"),
            "hermes", Set.of("enabled", "planner", "baseUrl", "adminToken"));

    private final RuntimeAiConfig cfg;
    private final HermesProperties hermesProps;
    private final HermesBotClient hermesAdmin;
    private final OllamaClient ollama;
    private final AiSettingsStore store;
    private final UrlGuard guard;
    private final OwnerAuth auth;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public SettingsAiService(RuntimeAiConfig cfg, HermesProperties hermesProps, HermesBotClient hermesAdmin, OllamaClient ollama,
                             AiSettingsStore store, UrlGuard guard, OwnerAuth auth, ObjectMapper mapper) {
        this.cfg = cfg;
        this.hermesProps = hermesProps;
        this.hermesAdmin = hermesAdmin;
        this.ollama = ollama;
        this.store = store;
        this.guard = guard;
        this.auth = auth;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ đọc
    public SettingsAiDto.Settings settings() {
        var c = cfg.get();
        var llm = new SettingsAiDto.Llm(c.local() ? "local" : "fake", c.chatBaseUrl(), c.model(), c.reasoningEffort(),
                (int) c.timeout().toSeconds(), c.maxOutputTokens(), !c.ollamaApiKey().isBlank(), c.viaOmniroute(), new LinkedHashMap<>(c.aliases()));
        var om = new SettingsAiDto.Omniroute(c.viaOmniroute(), c.omniBaseUrl(), origin(c.omniBaseUrl()), !c.omniApiKey().isBlank());
        var hermes = new SettingsAiDto.Hermes(c.hermesEnabled(), c.hermesPlanner(), c.hermesBaseUrl(), null, hermesAdmin.configured());
        // Có OWNER_TOKEN thì sửa được ("database"), không thì chỉ xem ("env"). Đổi có hiệu lực ngay nên applyMode = live.
        return new SettingsAiDto.Settings("live", auth.enabled() ? "database" : "env", auth.enabled(), llm,
                new SettingsAiDto.Ollama(c.ollamaBaseUrl(), c.model()), om, hermes);
    }

    private static String origin(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (RuntimeException e) {
            return url;
        }
    }

    // ------------------------------------------------------------------ sửa
    /**
     * PUT từng phần (xem SettingsAiPatch bên FE). Trường vắng mặt = giữ nguyên. Khoá bí mật: chuỗi = đặt/đổi, null = xoá.
     * Kiểm tra TOÀN BỘ trước khi ghi gì, nên một trường sai thì không có trường nào được lưu.
     */
    public SettingsAiDto.Settings update(JsonNode patch) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) throw bad("Body phải là object có ít nhất một nhóm");
        Map<String, String> plain = new HashMap<>();
        Map<String, String> secrets = new HashMap<>();
        patch.fieldNames().forEachRemaining(g -> {
            if (!FIELDS.containsKey(g)) throw bad("Nhóm không hợp lệ: " + g);
            JsonNode grp = patch.get(g);
            if (!grp.isObject()) throw bad(g + " phải là object");
            grp.fieldNames().forEachRemaining(f -> {
                if (!FIELDS.get(g).contains(f)) throw bad("Không được sửa: " + g + "." + f);
            });
        });

        JsonNode llm = patch.path("llm"), ol = patch.path("ollama"), om = patch.path("omniroute"), hm = patch.path("hermes");
        if (llm.has("provider")) plain.put(RuntimeAiConfig.PROVIDER, oneOf("llm.provider", llm.get("provider"), Set.of("fake", "local")));
        if (llm.has("reasoningEffort")) plain.put(RuntimeAiConfig.REASONING, oneOf("llm.reasoningEffort", llm.get("reasoningEffort"), EFFORT));
        if (llm.has("timeoutSec")) plain.put(RuntimeAiConfig.TIMEOUT, String.valueOf(intIn("llm.timeoutSec", llm.get("timeoutSec"), 1, 3600)));
        if (llm.has("maxOutputTokens")) plain.put(RuntimeAiConfig.MAX_OUTPUT, String.valueOf(intIn("llm.maxOutputTokens", llm.get("maxOutputTokens"), 1, 200_000)));
        if (llm.has("modelAliases")) plain.put(RuntimeAiConfig.ALIASES, aliases(llm.get("modelAliases")));
        if (ol.has("baseUrl")) plain.put(RuntimeAiConfig.OLLAMA_URL, guard.check("ollama.baseUrl", text("ollama.baseUrl", ol.get("baseUrl"), 300)));
        if (ol.has("defaultModel")) plain.put(RuntimeAiConfig.MODEL, line("ollama.defaultModel", ol.get("defaultModel"), 1, 200));
        if (om.has("baseUrl")) plain.put(RuntimeAiConfig.OMNI_URL, guard.check("omniroute.baseUrl", text("omniroute.baseUrl", om.get("baseUrl"), 300)));
        if (hm.has("planner")) plain.put(RuntimeAiConfig.HERMES_PLANNER, oneOf("hermes.planner", hm.get("planner"), Set.of("hermes", "llm")));
        if (hm.has("baseUrl")) {
            String u = text("hermes.baseUrl", hm.get("baseUrl"), 300);
            plain.put(RuntimeAiConfig.HERMES_URL, u.isBlank() ? "" : guard.check("hermes.baseUrl", u));
        }
        if (hm.has("enabled")) plain.put(RuntimeAiConfig.HERMES_ENABLED, String.valueOf(bool("hermes.enabled", hm.get("enabled"))));
        // Hai cờ bật OmniRoute luôn đi cùng nhau; lệch nhau là lỗi của người gọi
        if (llm.has("viaOmniroute") || om.has("enabled")) {
            Boolean a = llm.has("viaOmniroute") ? bool("llm.viaOmniroute", llm.get("viaOmniroute")) : null;
            Boolean b = om.has("enabled") ? bool("omniroute.enabled", om.get("enabled")) : null;
            if (a != null && b != null && !a.equals(b)) throw bad("llm.viaOmniroute và omniroute.enabled phải cùng giá trị");
            plain.put(RuntimeAiConfig.VIA_OMNI, String.valueOf(a != null ? a : b));
        }
        secret(secrets, RuntimeAiConfig.SECRET_LLM, llm, "apiKey");
        secret(secrets, RuntimeAiConfig.SECRET_OMNI, om, "apiKey");
        secret(secrets, RuntimeAiConfig.SECRET_ADMIN, hm, "adminToken");

        // Kiểm trạng thái SAU khi sửa: bật Hermes thì phải có địa chỉ; chế độ thật thì phải có model
        var c = cfg.get();
        boolean hermesOn = Boolean.parseBoolean(plain.getOrDefault(RuntimeAiConfig.HERMES_ENABLED, String.valueOf(c.hermesEnabled())));
        String hermesUrl = plain.getOrDefault(RuntimeAiConfig.HERMES_URL, c.hermesBaseUrl());
        if (hermesOn && hermesUrl.isBlank()) throw bad("hermes.baseUrl: bật Hermes thì phải có địa chỉ");
        if ("local".equals(plain.getOrDefault(RuntimeAiConfig.PROVIDER, c.provider())) && plain.getOrDefault(RuntimeAiConfig.MODEL, c.model()).isBlank())
            throw bad("ollama.defaultModel: chế độ AI thật cần có model mặc định");

        store.save(plain, secrets);   // giao dịch riêng, commit xong mới nạp lại
        store.reload();
        log.info("Cài đặt AI đã đổi: {} trường, {} khoá bí mật (không ghi giá trị)", plain.size(), secrets.size());
        return settings();
    }

    private void secret(Map<String, String> out, String key, JsonNode group, String field) {
        if (!group.has(field)) return;
        JsonNode v = group.get(field);
        if (v.isNull()) { out.put(key, null); return; }
        if (!v.isTextual()) throw bad(key + " phải là chuỗi hoặc null");
        String s = v.asText().trim();
        if (s.isEmpty() || s.length() > 500 || s.chars().anyMatch(Character::isISOControl)) throw bad(key + ": khoá phải là một dòng 1-500 ký tự");
        out.put(key, s);
    }

    private String aliases(JsonNode n) {
        if (!n.isObject() || n.size() > 50) throw bad("llm.modelAliases phải là object tối đa 50 mục");
        Map<String, String> m = new LinkedHashMap<>();
        n.fields().forEachRemaining(e -> {
            if (!ALIAS_KEY.matcher(e.getKey()).matches()) throw bad("Tên alias không hợp lệ: " + e.getKey());
            m.put(e.getKey(), line("llm.modelAliases." + e.getKey(), e.getValue(), 1, 200));
        });
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw bad("llm.modelAliases không đọc được");
        }
    }

    private static String text(String field, JsonNode n, int max) {
        if (!n.isTextual()) throw bad(field + " phải là chuỗi");
        String s = n.asText().trim();
        if (s.length() > max) throw bad(field + " tối đa " + max + " ký tự");
        return s;
    }

    private static String line(String field, JsonNode n, int min, int max) {
        String s = text(field, n, max);
        if (s.length() < min || s.chars().anyMatch(Character::isISOControl)) throw bad(field + " phải là một dòng " + min + "-" + max + " ký tự");
        return s;
    }

    private static String oneOf(String field, JsonNode n, Set<String> allowed) {
        String s = text(field, n, 40);
        if (!allowed.contains(s)) throw bad(field + " phải là một trong: " + allowed);
        return s;
    }

    private static int intIn(String field, JsonNode n, int lo, int hi) {
        if (!n.isInt() || n.asInt() < lo || n.asInt() > hi) throw bad(field + " phải là số nguyên từ " + lo + " đến " + hi);
        return n.asInt();
    }

    private static boolean bool(String field, JsonNode n) {
        if (!n.isBoolean()) throw bad(field + " phải là true/false");
        return n.asBoolean();
    }

    private static AppException bad(String msg) { return new AppException(ErrorCode.VALIDATION, msg); }

    // ------------------------------------------------------------------ tình trạng
    public SettingsAiDto.Status status() {
        var c = cfg.get();
        SettingsAiDto.Health o = timed(() -> ollama.version(), j -> "Ollama " + j.path("version").asText("?"));
        SettingsAiDto.Health llm;
        if (!c.local()) {
            llm = new SettingsAiDto.Health("unknown", null, now(), "Đang dùng AI giả");
        } else if (!"up".equals(o.state()) && !"degraded".equals(o.state())) {
            llm = new SettingsAiDto.Health("down", o.latencyMs(), now(), o.detail());
        } else {
            llm = modelPresent(c.model())
                    ? new SettingsAiDto.Health(o.state(), o.latencyMs(), now(), "Model " + c.model() + " sẵn sàng")
                    : new SettingsAiDto.Health("degraded", o.latencyMs(), now(), "Model " + c.model() + " chưa có trên Ollama");
        }
        SettingsAiDto.Health hermes = c.hermesBaseUrl().isBlank()
                ? new SettingsAiDto.Health("unknown", null, now(), "Chưa khai báo địa chỉ Hermes")
                : timed(this::hermesHealth, j -> "Hermes phản hồi");
        SettingsAiDto.Health omni = c.viaOmniroute()
                ? timed(this::omnirouteHealth, j -> "OmniRoute phản hồi")
                : new SettingsAiDto.Health("unknown", null, now(), "OmniRoute chưa bật");
        return new SettingsAiDto.Status(o, omni, llm, hermes);
    }

    private boolean modelPresent(String name) {
        try {
            for (JsonNode m : ollama.tags().path("models")) if (name.equals(m.path("name").asText())) return true;
            return false;
        } catch (AppException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ model
    /** Model có trên Ollama kèm khả năng (tools, thinking) lấy từ /api/show. */
    public SettingsAiDto.Models models(String source) {
        if (!"ollama".equals(source)) throw new AppException(ErrorCode.LLM_UNAVAILABLE, "OmniRoute chưa hỗ trợ liệt kê model ở đây");
        JsonNode tags = ollama.tags();
        JsonNode running = safeRunning();
        List<SettingsAiDto.ModelItem> out = new ArrayList<>();
        for (JsonNode m : tags.path("models")) {
            String name = m.path("name").asText();
            Boolean tools = null, thinking = null;
            try {
                JsonNode caps = ollama.show(name).path("capabilities");
                if (caps.isArray()) {
                    tools = false; thinking = false;
                    for (JsonNode cap : caps) {
                        if ("tools".equals(cap.asText())) tools = true;
                        if ("thinking".equals(cap.asText())) thinking = true;
                    }
                }
            } catch (AppException ignored) { /* thiếu khả năng thì để null, vẫn liệt kê model */ }
            out.add(new SettingsAiDto.ModelItem(name, label(m, isLoaded(running, name)), tools, thinking));
        }
        return new SettingsAiDto.Models(out);
    }

    private JsonNode safeRunning() {
        try { return ollama.running(); } catch (AppException e) { return null; }
    }

    private static boolean isLoaded(JsonNode running, String name) {
        if (running == null) return false;
        for (JsonNode m : running.path("models")) if (name.equals(m.path("name").asText())) return true;
        return false;
    }

    private static String label(JsonNode m, boolean loaded) {
        JsonNode d = m.path("details");
        StringBuilder sb = new StringBuilder(m.path("name").asText());
        String params = d.path("parameter_size").asText("");
        String quant = d.path("quantization_level").asText("");
        double gb = m.path("size").asLong(0) / 1e9;
        if (!params.isEmpty() || !quant.isEmpty()) sb.append(" · ").append((params + " " + quant).trim());
        if (gb > 0) sb.append(String.format(java.util.Locale.ROOT, " · %.1f GB", gb));
        if (loaded) sb.append(" · đang nạp");
        return sb.toString();
    }

    // ------------------------------------------------------------------ thử kết nối
    /** overrides bị bỏ qua có chủ đích: chỉ thử đúng địa chỉ đã lưu (chống SSRF); model thì phải có trên Ollama. */
    public SettingsAiDto.TestResult test(SettingsAiDto.TestBody body) {
        var c = cfg.get();
        String target = body.target() == null ? "" : body.target();
        long t0 = System.nanoTime();
        try {
            switch (target) {
                case "ollama": {
                    JsonNode tags = ollama.tags();
                    List<String> names = new ArrayList<>();
                    for (JsonNode m : tags.path("models")) names.add(m.path("name").asText());
                    return ok(t0, "Ollama phản hồi, có " + names.size() + " model", names, null);
                }
                case "llm": {
                    if (!c.local()) throw new AppException(ErrorCode.LLM_UNAVAILABLE, "Đang dùng AI giả, chưa có model thật để thử");
                    String model = body.overrides() != null && body.overrides().get("model") != null && !body.overrides().get("model").isBlank()
                            ? body.overrides().get("model").trim() : c.model();
                    if (!c.viaOmniroute() && !modelPresent(model)) throw new AppException(ErrorCode.LLM_UNAVAILABLE, "Model \"" + model + "\" không có trên Ollama");
                    String sample = ollama.chatOnce(model, "Trả lời đúng một từ: OK");
                    return ok(t0, "Model " + model + " trả lời được", null, sample);
                }
                case "hermes": {
                    if (c.hermesBaseUrl().isBlank()) throw new AppException(ErrorCode.HERMES_NOT_CONFIGURED, "Chưa khai báo địa chỉ Hermes");
                    hermesHealth();
                    return ok(t0, "Hermes phản hồi", null, null);
                }
                case "omniroute": {
                    if (!c.viaOmniroute()) throw new AppException(ErrorCode.LLM_UNAVAILABLE, "OmniRoute chưa bật");
                    omnirouteHealth();
                    return ok(t0, "OmniRoute phản hồi", null, null);
                }
                default:
                    throw new AppException(ErrorCode.VALIDATION, "target phải là ollama, llm, hermes hoặc omniroute");
            }
        } catch (AppException e) {
            if (e.code() == ErrorCode.VALIDATION) throw e;
            return new SettingsAiDto.TestResult(false, ms(t0), e.getMessage(), e.code().name(), CorrelationIdFilter.current(), null, null);
        }
    }

    private SettingsAiDto.TestResult ok(long t0, String msg, List<String> models, String sample) {
        return new SettingsAiDto.TestResult(true, ms(t0), msg, null, CorrelationIdFilter.current(), models, sample);
    }

    // ------------------------------------------------------------------ helpers
    private JsonNode hermesHealth() {
        return ping(cfg.get().hermesBaseUrl().trim().replaceAll("/+$", "") + "/health", hermesProps.apiKey(), ErrorCode.HERMES_UNAVAILABLE, "Hermes");
    }

    private JsonNode omnirouteHealth() {
        var c = cfg.get();
        return ping(c.omniBaseUrl().trim().replaceAll("/+$", "") + "/models", c.omniApiKey(), ErrorCode.LLM_UNAVAILABLE, "OmniRoute");
    }

    private JsonNode ping(String url, String key, ErrorCode code, String name) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET();
            if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key.trim());
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new AppException(code, name + " trả HTTP " + r.statusCode());
            return null;
        } catch (IOException e) {
            throw new AppException(code, "Không kết nối được " + name, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(code, "Bị ngắt khi gọi " + name, null, e);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.HERMES_NOT_CONFIGURED, "Địa chỉ " + name + " không hợp lệ", null, e);
        }
    }

    private SettingsAiDto.Health timed(Supplier<JsonNode> call, java.util.function.Function<JsonNode, String> okDetail) {
        long t0 = System.nanoTime();
        try {
            JsonNode r = call.get();
            long ms = ms(t0);
            return new SettingsAiDto.Health(ms > SLOW_MS ? "degraded" : "up", ms, now(), ms > SLOW_MS ? "Phản hồi chậm (" + ms + " ms)" : okDetail.apply(r));
        } catch (AppException e) {
            return new SettingsAiDto.Health("down", ms(t0), now(), e.getMessage());
        }
    }

    private static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    private static String now() { return Instant.now().toString(); }
}

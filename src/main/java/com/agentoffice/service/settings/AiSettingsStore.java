package com.agentoffice.service.settings;

import com.agentoffice.config.RuntimeAiConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lưu cài đặt AI chỉnh từ giao diện vào bảng ai_setting và nạp lại vào RuntimeAiConfig. Khoá bí mật luôn lưu dạng mã hoá (SettingsCrypto).
 * Nạp khi BE sẵn sàng (sau Flyway), nên trước đó BE chạy bằng giá trị mặc định từ .env.
 */
@Component
public class AiSettingsStore {
    private static final Logger log = LoggerFactory.getLogger(AiSettingsStore.class);

    private final JdbcTemplate jdbc;
    private final SettingsCrypto crypto;
    private final RuntimeAiConfig cfg;

    public AiSettingsStore(JdbcTemplate jdbc, SettingsCrypto crypto, RuntimeAiConfig cfg) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.cfg = cfg;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            reload();
        } catch (RuntimeException e) {
            log.warn("Không nạp được cài đặt AI từ DB, dùng giá trị mặc định từ .env: {}", e.toString());
        }
    }

    /** Đọc toàn bộ ai_setting rồi áp vào cấu hình đang chạy. Khoá không giải mã được (đổi khoá chủ) bị bỏ qua, có cảnh báo. */
    public void reload() {
        Map<String, String> plain = new HashMap<>();
        Map<String, String> secrets = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT key, value, secret FROM ai_setting")) {
            String key = (String) r.get("key"), value = (String) r.get("value");
            if (Boolean.TRUE.equals(r.get("secret"))) {
                if (!crypto.ready()) { log.warn("Có khoá '{}' trong DB nhưng chưa có AGENTOFFICE_MASTER_KEY: bỏ qua", key); continue; }
                try {
                    secrets.put(key, crypto.open(key, value));
                } catch (RuntimeException e) {
                    log.warn("Không giải mã được khoá '{}' (đã đổi khoá chủ?): bỏ qua", key);
                }
            } else {
                plain.put(key, value);
            }
        }
        cfg.apply(plain, secrets);
    }

    /**
     * Ghi một lần trong một giao dịch. plain: khoá -> giá trị (null = xoá, về mặc định .env).
     * secrets: khoá -> khoá API (null = xoá). Gọi reload() SAU khi giao dịch này đã commit.
     */
    @Transactional
    public void save(Map<String, String> plain, Map<String, String> secrets) {
        for (Map.Entry<String, String> e : plain.entrySet()) put(e.getKey(), e.getValue(), false);
        for (Map.Entry<String, String> e : secrets.entrySet()) put(e.getKey(), e.getValue() == null ? null : crypto.seal(e.getKey(), e.getValue()), true);
    }

    private void put(String key, String value, boolean secret) {
        if (value == null) {
            jdbc.update("DELETE FROM ai_setting WHERE key = ?", key);
            return;
        }
        jdbc.update("INSERT INTO ai_setting(key, value, secret, updated_at) VALUES (?, ?, ?, now()) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, secret = EXCLUDED.secret, updated_at = now()", key, value, secret);
    }

    /** Tên các khoá đã có trong DB (để biết có đang ghi đè không). */
    public List<String> keys() { return jdbc.queryForList("SELECT key FROM ai_setting", String.class); }
}

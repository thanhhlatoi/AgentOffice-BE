package com.agentoffice.service.settings;

import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mã hoá khoá API trước khi lưu DB: AES-256-GCM, IV 12 byte ngẫu nhiên MỖI lần, tên cài đặt làm AAD (chép ciphertext
 * từ dòng này sang dòng khác sẽ giải mã thất bại). Khoá chủ lấy từ agentoffice.secrets.master-key-base64 (32 byte, base64).
 * Định dạng lưu: v1:&lt;iv base64&gt;:&lt;ciphertext+tag base64&gt;. Không bao giờ log plaintext.
 */
@Component
public class SettingsCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SettingsCrypto(@Value("${agentoffice.secrets.master-key-base64:}") String masterKeyBase64) {
        SecretKeySpec k = null;
        if (masterKeyBase64 != null && !masterKeyBase64.isBlank()) {
            try {
                byte[] raw = Base64.getDecoder().decode(masterKeyBase64.trim());
                if (raw.length == 32) k = new SecretKeySpec(raw, "AES");
            } catch (IllegalArgumentException ignored) { /* khoá chủ sai định dạng: coi như chưa cấu hình */ }
        }
        this.key = k;
    }

    public boolean ready() { return key != null; }

    public String seal(String aad, String plaintext) {
        requireReady();
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (GeneralSecurityException e) {
            throw new AppException(ErrorCode.INTERNAL, "Không mã hoá được khoá", null, e);
        }
    }

    public String open(String aad, String sealed) {
        requireReady();
        try {
            String[] p = sealed.split(":", 3);
            if (p.length != 3 || !"v1".equals(p[0])) throw new GeneralSecurityException("định dạng không hợp lệ");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(p[1])));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(Base64.getDecoder().decode(p[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new AppException(ErrorCode.INTERNAL, "Không giải mã được khoá đã lưu (đổi khoá chủ?)", null, e);
        }
    }

    private void requireReady() {
        if (key == null) throw new AppException(ErrorCode.VALIDATION,
                "Chưa có khoá chủ để mã hoá: đặt AGENTOFFICE_MASTER_KEY (32 byte base64) trong .env rồi khởi động lại BE");
    }
}

package com.agentoffice.service.tool;

/**
 * ===== BẠN TỰ VIẾT ===== Mã hoá secret bằng AES-256-GCM (javax.crypto), key từ agentoffice.secrets.master-key-base64.
 * IV 12 byte ngẫu nhiên MỖI lần mã hoá (SecureRandom), lưu cạnh ciphertext. Không bao giờ log plaintext.
 */
public class SecretService {
    public record Sealed(byte[] ciphertext, byte[] iv) {}

    public Sealed seal(String plaintext) { throw new UnsupportedOperationException("TODO: tự viết"); }
    public String open(Sealed sealed) { throw new UnsupportedOperationException("TODO: tự viết"); }
}

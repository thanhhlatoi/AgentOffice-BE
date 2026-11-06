-- V17: cài đặt AI chỉnh từ giao diện (ghi đè giá trị mặc định lấy từ .env). Khoá bí mật (secret = true) lưu dạng mã hoá AES-GCM, KHÔNG bao giờ plaintext.
CREATE TABLE ai_setting (
    key        VARCHAR(80) PRIMARY KEY,
    value      TEXT        NOT NULL,
    secret     BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

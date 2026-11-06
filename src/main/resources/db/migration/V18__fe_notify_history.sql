-- Hộp thư trong ứng dụng + lịch sử hội thoại cho FE (docs FE MISSING_API mục I4, I6)

-- Thông báo trong app: dùng chung bảng notification (channel = IN_APP), thêm mức độ, đối tượng đích, đã đọc
ALTER TABLE notification
    ADD COLUMN level       VARCHAR(10) NOT NULL DEFAULT 'INFO',   -- INFO|SUCCESS|WARNING|CRITICAL
    ADD COLUMN target_kind VARCHAR(20),                           -- TASK|GOAL|APPROVAL|DEVICE|BUDGET|PROJECT|AGENT
    ADD COLUMN target_id   UUID,
    ADD COLUMN read_at     TIMESTAMPTZ;
CREATE INDEX idx_notification_inbox ON notification(created_at DESC, id DESC) WHERE channel = 'IN_APP';
CREATE UNIQUE INDEX ux_notification_inapp_dedupe ON notification(dedupe_key) WHERE channel = 'IN_APP' AND dedupe_key IS NOT NULL;

-- Một dòng duy nhất: quy tắc / giờ yên lặng / tóm tắt cuối ngày do chủ đặt (tài liệu JSON theo hợp đồng FE)
CREATE TABLE notification_settings (
    id         SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    doc        JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Hội thoại: mỗi task một cuộc; tin nhắn mang token/chi phí/công cụ
CREATE UNIQUE INDEX ux_conversation_task ON conversation(task_id) WHERE task_id IS NOT NULL;
ALTER TABLE message
    ADD COLUMN tokens    INT         NOT NULL DEFAULT 0,
    ADD COLUMN cost_micro BIGINT     NOT NULL DEFAULT 0,
    ADD COLUMN tool_name VARCHAR(100),
    ADD COLUMN tool_args JSONB,
    ADD COLUMN has_error BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN truncated BOOLEAN     NOT NULL DEFAULT FALSE;
CREATE INDEX idx_conversation_agent ON conversation(agent_id, created_at DESC);

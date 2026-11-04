-- Giai đoạn 3B/3C
CREATE TABLE project (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE goal ADD CONSTRAINT fk_goal_project FOREIGN KEY (project_id) REFERENCES project(id);

CREATE TABLE conversation (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id    UUID REFERENCES task(id),
    agent_id   UUID REFERENCES agent(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE message (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,        -- SYSTEM|USER|ASSISTANT|TOOL
    content         TEXT NOT NULL,               -- đã qua SecretRedactor
    content_search  TEXT,                        -- đã bỏ dấu, dùng để tìm kiếm
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_message_conv ON message(conversation_id, created_at);

CREATE TABLE notification (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type        VARCHAR(40) NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        TEXT,
    dedupe_key  VARCHAR(200),
    channel     VARCHAR(20) NOT NULL,            -- IN_APP|TELEGRAM|EMAIL
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING|SENT|FAILED|SUPPRESSED
    attempts    INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ
);
CREATE INDEX idx_notification_dedupe ON notification(dedupe_key, created_at);

CREATE TABLE notification_pref (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel      VARCHAR(20) NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    quiet_start  TIME,
    quiet_end    TIME,
    target       VARCHAR(200)                    -- telegram chat id / email
);

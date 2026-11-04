-- Giai đoạn 2: agent, goal, task, dependency, approval, domain_event (outbox)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TABLE agent (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100) NOT NULL,
    role          VARCHAR(100) NOT NULL,
    department    VARCHAR(50)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'IDLE',   -- IDLE|WORKING|BLOCKED|ERROR|OFFLINE
    model_profile VARCHAR(50),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE goal (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       VARCHAR(300) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE|DONE|CANCELLED
    project_id  UUID,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE task (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id          UUID NOT NULL REFERENCES goal(id),
    parent_task_id   UUID REFERENCES task(id),
    assignee_id      UUID REFERENCES agent(id),
    title            VARCHAR(300) NOT NULL,
    instruction      TEXT,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    priority         VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',
    depth            INT          NOT NULL DEFAULT 0,
    attempt          INT          NOT NULL DEFAULT 0,
    max_attempts     INT          NOT NULL DEFAULT 3,
    next_run_at      TIMESTAMPTZ,
    cancel_requested BOOLEAN      NOT NULL DEFAULT FALSE,
    result           TEXT,
    error            TEXT,
    kanban_rank      VARCHAR(64),                          -- giai đoạn 3 (lexorank)
    version          BIGINT       NOT NULL DEFAULT 0,      -- optimistic lock
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_status_prio ON task(status, priority, next_run_at);
CREATE INDEX idx_task_goal ON task(goal_id);

CREATE TABLE task_dependency (
    task_id       UUID NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    depends_on_id UUID NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, depends_on_id),
    CHECK (task_id <> depends_on_id)
);

CREATE TABLE approval (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id         UUID NOT NULL REFERENCES task(id),
    agent_id        UUID NOT NULL REFERENCES agent(id),
    tool_name       VARCHAR(100) NOT NULL,
    risk_level      VARCHAR(20)  NOT NULL,   -- READ|WRITE_INTERNAL|EXTERNAL|MONEY|DESTRUCTIVE
    args_snapshot   JSONB        NOT NULL,   -- chạy đúng cái này, không chạy lại từ đầu
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING|APPROVED|REJECTED|EXPIRED
    decided_by      VARCHAR(100),
    decided_at      TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE approval_policy (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tool_name  VARCHAR(100),
    risk_level VARCHAR(20) NOT NULL,
    mode       VARCHAR(20) NOT NULL   -- AUTO|ASK|DENY
);

-- Outbox: id tăng dần = thứ tự sự kiện, FE dùng lastEventId để bắt kịp.
CREATE TABLE domain_event (
    id           BIGSERIAL PRIMARY KEY,
    type         VARCHAR(60)  NOT NULL,
    aggregate_id UUID,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE system_state (
    key   VARCHAR(50) PRIMARY KEY,
    value VARCHAR(200) NOT NULL
);
INSERT INTO system_state(key, value) VALUES ('paused', 'false');

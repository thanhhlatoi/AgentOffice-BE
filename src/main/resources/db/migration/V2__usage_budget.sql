-- Giai đoạn 3A: chi phí token. Tiền lưu micro-USD (BIGINT), không dùng float.
CREATE TABLE model_price (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model                  VARCHAR(100) NOT NULL,
    input_micro_per_mtok   BIGINT NOT NULL,
    output_micro_per_mtok  BIGINT NOT NULL,
    effective_from         TIMESTAMPTZ NOT NULL
);

CREATE TABLE usage_record (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id       UUID REFERENCES agent(id),
    task_id        UUID REFERENCES task(id),
    model          VARCHAR(100) NOT NULL,
    input_tokens   BIGINT NOT NULL,
    output_tokens  BIGINT NOT NULL,
    cost_micro_usd BIGINT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_usage_created ON usage_record(created_at);

CREATE TABLE usage_daily (
    day            DATE NOT NULL,
    agent_id       UUID NOT NULL,
    model          VARCHAR(100) NOT NULL,
    input_tokens   BIGINT NOT NULL DEFAULT 0,
    output_tokens  BIGINT NOT NULL DEFAULT 0,
    cost_micro_usd BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (day, agent_id, model)
);

CREATE TABLE budget (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope        VARCHAR(20) NOT NULL,       -- GLOBAL|AGENT|PROJECT
    scope_id     UUID,
    period       VARCHAR(10) NOT NULL,       -- DAY|MONTH
    limit_micro  BIGINT NOT NULL,
    on_exceed    VARCHAR(20) NOT NULL DEFAULT 'WAIT'  -- WAIT|BLOCK|WARN
);

CREATE TABLE budget_counter (
    budget_id      UUID NOT NULL REFERENCES budget(id),
    period_start   DATE NOT NULL,
    reserved_micro BIGINT NOT NULL DEFAULT 0,
    spent_micro    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (budget_id, period_start)
);

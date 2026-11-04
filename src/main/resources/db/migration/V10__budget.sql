-- V10: ngân sách + department + chờ ngân sách (Lát 2). Chỉ ALTER/CREATE, không DROP dữ liệu.

-- ---------------------------------------------------------------- department
CREATE TABLE department (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(50) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO department (name)
SELECT department FROM agent UNION SELECT department FROM agent_template
ON CONFLICT (name) DO NOTHING;
ALTER TABLE agent ADD COLUMN department_id UUID REFERENCES department(id);
UPDATE agent a SET department_id = d.id FROM department d WHERE d.name = a.department;
-- goal.department_id từ V7 là tham chiếu mềm tới bảng chưa tồn tại: giá trị không khớp department nào thì không có nghĩa, đưa về NULL rồi mới thêm FK
UPDATE goal SET department_id = NULL WHERE department_id IS NOT NULL AND department_id NOT IN (SELECT id FROM department);
ALTER TABLE goal ADD CONSTRAINT fk_goal_department FOREIGN KEY (department_id) REFERENCES department(id);

-- ---------------------------------------------------------------- budget
ALTER TABLE budget RENAME COLUMN scope TO scope_type;
ALTER TABLE budget RENAME COLUMN limit_micro TO limit_micro_usd;
ALTER TABLE budget RENAME COLUMN on_exceed TO hard_action;
UPDATE budget SET hard_action = CASE hard_action WHEN 'WARN' THEN 'WARN_ONLY' ELSE 'BLOCK' END;   -- V2: WAIT|BLOCK|WARN
ALTER TABLE budget ALTER COLUMN hard_action SET DEFAULT 'BLOCK';
ALTER TABLE budget
    ADD COLUMN limit_tokens BIGINT,
    ADD COLUMN soft_pct     INT         NOT NULL DEFAULT 80,
    ADD COLUMN enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN created_at   TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE budget ADD CONSTRAINT budget_scope_chk  CHECK (scope_type IN ('GLOBAL','DEPARTMENT','AGENT','PROJECT','GOAL'));
ALTER TABLE budget ADD CONSTRAINT budget_period_chk CHECK (period IN ('DAY','MONTH','TOTAL'));
ALTER TABLE budget ADD CONSTRAINT budget_action_chk CHECK (hard_action IN ('BLOCK','WARN_ONLY'));
ALTER TABLE budget ADD CONSTRAINT budget_limit_chk  CHECK (limit_micro_usd > 0 AND (limit_tokens IS NULL OR limit_tokens > 0));
ALTER TABLE budget ADD CONSTRAINT budget_soft_chk   CHECK (soft_pct BETWEEN 1 AND 100);
ALTER TABLE budget ADD CONSTRAINT budget_global_chk CHECK ((scope_type = 'GLOBAL') = (scope_id IS NULL));
CREATE UNIQUE INDEX uq_budget_scope ON budget (scope_type, COALESCE(scope_id, '00000000-0000-0000-0000-000000000000'), period);

-- Hạn mức MẪU (chủ chưa điền): GLOBAL ngày 5 USD, tháng 100 USD; mỗi agent 1 USD/ngày. Đổi bằng POST /api/budgets/{id}/raise.
INSERT INTO budget (scope_type, scope_id, period, limit_micro_usd) VALUES
    ('GLOBAL', NULL, 'DAY',    5000000),
    ('GLOBAL', NULL, 'MONTH', 100000000)
ON CONFLICT DO NOTHING;
INSERT INTO budget (scope_type, scope_id, period, limit_micro_usd)
SELECT 'AGENT', id, 'DAY', 1000000 FROM agent
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- budget_counter
-- period_start: DAY = ngày (múi giờ báo cáo), MONTH = mùng 1, TOTAL = '1970-01-01'. Giữ tên cột V2 (reserved_micro, spent_micro).
ALTER TABLE budget_counter
    ADD COLUMN spent_tokens  BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN soft_alerted  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN hard_alerted  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE budget_counter ADD CONSTRAINT budget_counter_nonneg_chk
    CHECK (spent_micro >= 0 AND reserved_micro >= 0 AND spent_tokens >= 0);

-- ---------------------------------------------------------------- budget_change (chỉ thêm)
CREATE TABLE budget_change (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    budget_id          UUID NOT NULL REFERENCES budget(id),
    old_limit_micro_usd BIGINT NOT NULL,
    new_limit_micro_usd BIGINT NOT NULL,
    reason             VARCHAR(500) NOT NULL,
    changed_by         VARCHAR(100) NOT NULL,
    changed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_budget_change_budget ON budget_change (budget_id, changed_at);
CREATE FUNCTION budget_change_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'budget_change is append-only (% blocked)', TG_OP;
END $$;
CREATE TRIGGER trg_budget_change_rowlock BEFORE UPDATE OR DELETE ON budget_change
    FOR EACH ROW EXECUTE FUNCTION budget_change_immutable();
CREATE TRIGGER trg_budget_change_truncate BEFORE TRUNCATE ON budget_change
    FOR EACH STATEMENT EXECUTE FUNCTION budget_change_immutable();

-- ---------------------------------------------------------------- task: chờ ngân sách + tách thời gian chạy/chờ
ALTER TABLE task
    ADD COLUMN waiting_budget_id   UUID REFERENCES budget(id),    -- ngân sách chặn (để biết khi nào chạy tiếp được)
    ADD COLUMN waiting_need_micro  BIGINT,                        -- phần ước tính bị từ chối
    ADD COLUMN status_since        TIMESTAMPTZ,                   -- vào trạng thái hiện tại lúc nào (NULL = createdAt)
    ADD COLUMN active_duration_sec BIGINT NOT NULL DEFAULT 0 CHECK (active_duration_sec >= 0),   -- thời gian RUNNING
    ADD COLUMN wait_duration_sec   BIGINT NOT NULL DEFAULT 0 CHECK (wait_duration_sec >= 0);     -- WAITING_APPROVAL + WAITING_BUDGET
CREATE INDEX idx_task_waiting_budget ON task (waiting_budget_id) WHERE status = 'WAITING_BUDGET';

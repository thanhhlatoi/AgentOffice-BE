-- V7: nâng cấp task engine / orchestrator / approval. Chỉ ALTER (an toàn trên DB đã có dữ liệu), không DROP bảng/cột.

-- ---------------------------------------------------------------- agent
ALTER TABLE agent ADD COLUMN max_concurrent_tasks INT NOT NULL DEFAULT 1 CHECK (max_concurrent_tasks >= 1);

-- ---------------------------------------------------------------- goal
UPDATE goal SET status = 'RUNNING'   WHERE status = 'ACTIVE';
UPDATE goal SET status = 'SUCCEEDED' WHERE status = 'DONE';
ALTER TABLE goal ALTER COLUMN status TYPE VARCHAR(30);   -- 'AWAITING_PLAN_APPROVAL' dài 22 ký tự, cột cũ chỉ 20
ALTER TABLE goal ALTER COLUMN status SET DEFAULT 'DRAFT';
ALTER TABLE goal
    ADD COLUMN department_id UUID,                          -- chưa có bảng department: chỉ là tham chiếu mềm
    ADD COLUMN token_budget  BIGINT CHECK (token_budget IS NULL OR token_budget >= 0),  -- NULL = không giới hạn
    ADD COLUMN tokens_used   BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN finished_at   TIMESTAMPTZ,
    ADD COLUMN plan          JSONB,                         -- kế hoạch Planner đề xuất gần nhất
    ADD COLUMN plan_attempts   INT NOT NULL DEFAULT 0,      -- tổng số lần Planner đã được gọi (để theo dõi)
    ADD COLUMN plan_rejections INT NOT NULL DEFAULT 0,      -- số lần chủ từ chối kế hoạch (tối đa 2 lần làm lại)
    ADD COLUMN result        TEXT,
    ADD COLUMN error         TEXT;
ALTER TABLE goal ADD CONSTRAINT goal_status_chk CHECK (status IN
    ('DRAFT','PLANNING','AWAITING_PLAN_APPROVAL','RUNNING','SUCCEEDED','FAILED','CANCELLED'));

-- ---------------------------------------------------------------- task
ALTER TABLE task RENAME COLUMN assignee_id  TO assignee_agent_id;
ALTER TABLE task RENAME COLUMN instruction  TO input;
ALTER TABLE task RENAME COLUMN attempt      TO attempts;
ALTER TABLE task ALTER COLUMN goal_id DROP NOT NULL;        -- POST /agents/{id}/tasks có thể không thuộc goal nào
ALTER TABLE task
    ADD COLUMN created_by  VARCHAR(40) NOT NULL DEFAULT 'HUMAN',   -- HUMAN | ORCHESTRATOR | <agentId>
    ADD COLUMN started_at  TIMESTAMPTZ,
    ADD COLUMN finished_at TIMESTAMPTZ;
ALTER TABLE task ADD CONSTRAINT task_status_chk CHECK (status IN
    ('PENDING','BLOCKED','QUEUED','RUNNING','WAITING_APPROVAL','WAITING_BUDGET','SUCCEEDED','FAILED','CANCELLED'));
ALTER TABLE task ADD CONSTRAINT task_priority_chk CHECK (priority IN ('HIGH','NORMAL','LOW'));
ALTER TABLE task ADD CONSTRAINT task_attempts_chk CHECK (attempts >= 0 AND max_attempts >= 1);

DROP INDEX IF EXISTS idx_task_status_prio;   -- thay bằng 2 chỉ mục dưới (chỉ mục, không phải dữ liệu)
CREATE INDEX idx_task_assignee_status_prio ON task (assignee_agent_id, status, priority, created_at);
CREATE INDEX idx_task_status_next_run      ON task (status, next_run_at);
CREATE INDEX idx_task_parent               ON task (parent_task_id);

-- ---------------------------------------------------------------- task_dependency
ALTER TABLE task_dependency RENAME COLUMN depends_on_id TO depends_on_task_id;
CREATE INDEX idx_task_dependency_depends_on ON task_dependency (depends_on_task_id);

-- ---------------------------------------------------------------- approval
-- Lựa chọn: GIỮ agent_id nhưng cho phép NULL (kind=PLAN không có agent). Thêm goal_id cho kind=PLAN
-- (lúc duyệt kế hoạch chưa có task nào). tool_name/risk_level chỉ bắt buộc với TOOL_CALL.
ALTER TABLE approval ALTER COLUMN task_id    DROP NOT NULL;
ALTER TABLE approval ALTER COLUMN agent_id   DROP NOT NULL;
ALTER TABLE approval ALTER COLUMN tool_name  DROP NOT NULL;
ALTER TABLE approval ALTER COLUMN risk_level DROP NOT NULL;
ALTER TABLE approval
    ADD COLUMN kind        VARCHAR(20) NOT NULL DEFAULT 'TOOL_CALL',
    ADD COLUMN goal_id     UUID REFERENCES goal(id),
    ADD COLUMN preview     TEXT,
    ADD COLUMN reason      TEXT,
    ADD COLUMN executed_at TIMESTAMPTZ;                      -- mốc tool.executeApproved đã chạy (chạy đúng 1 lần)
ALTER TABLE approval ADD CONSTRAINT approval_kind_chk   CHECK (kind IN ('PLAN','TOOL_CALL'));
ALTER TABLE approval ADD CONSTRAINT approval_status_chk CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED'));
ALTER TABLE approval ADD CONSTRAINT approval_shape_chk  CHECK (
    (kind = 'PLAN'      AND goal_id IS NOT NULL)
 OR (kind = 'TOOL_CALL' AND task_id IS NOT NULL AND tool_name IS NOT NULL AND risk_level IS NOT NULL));
CREATE INDEX idx_approval_status_expires ON approval (status, expires_at);
CREATE INDEX idx_approval_task           ON approval (task_id);

-- ---------------------------------------------------------------- approval_policy
ALTER TABLE approval_policy ADD COLUMN requires_approval BOOLEAN;
UPDATE approval_policy SET requires_approval = (mode <> 'AUTO');
ALTER TABLE approval_policy ALTER COLUMN requires_approval SET NOT NULL;
ALTER TABLE approval_policy ALTER COLUMN mode SET DEFAULT 'ASK';   -- cột cũ, không còn dùng; giữ để không mất dữ liệu
-- MONEY/DESTRUCTIVE: luôn bắt duyệt, ép ở tầng DB (ngoài kiểm tra ở service).
ALTER TABLE approval_policy ADD CONSTRAINT approval_policy_hard_chk
    CHECK (risk_level NOT IN ('MONEY','DESTRUCTIVE') OR requires_approval);
UPDATE approval_policy SET requires_approval = TRUE WHERE risk_level IN ('MONEY','DESTRUCTIVE');
CREATE UNIQUE INDEX uq_approval_policy ON approval_policy (COALESCE(tool_name, ''), risk_level);
INSERT INTO approval_policy (tool_name, risk_level, requires_approval, mode)
SELECT NULL, v.r, v.req, CASE WHEN v.req THEN 'ASK' ELSE 'AUTO' END
FROM (VALUES ('READ', FALSE), ('WRITE_INTERNAL', FALSE), ('EXTERNAL', TRUE), ('MONEY', TRUE), ('DESTRUCTIVE', TRUE)) AS v(r, req)
WHERE NOT EXISTS (SELECT 1 FROM approval_policy p WHERE p.tool_name IS NULL AND p.risk_level = v.r);

-- ---------------------------------------------------------------- approval_audit (append-only)
CREATE TABLE approval_audit (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_id UUID NOT NULL REFERENCES approval(id),
    actor       VARCHAR(100) NOT NULL,
    action      VARCHAR(30)  NOT NULL,     -- REQUESTED|APPROVED|REJECTED|EXPIRED|EXECUTED|EXECUTION_FAILED
    detail      JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_approval_audit_approval ON approval_audit (approval_id, created_at);

CREATE FUNCTION approval_audit_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'approval_audit is append-only (% blocked)', TG_OP;
END $$;
CREATE TRIGGER approval_audit_no_update BEFORE UPDATE OR DELETE ON approval_audit
    FOR EACH ROW EXECUTE FUNCTION approval_audit_immutable();
CREATE TRIGGER approval_audit_no_truncate BEFORE TRUNCATE ON approval_audit
    FOR EACH STATEMENT EXECUTE FUNCTION approval_audit_immutable();

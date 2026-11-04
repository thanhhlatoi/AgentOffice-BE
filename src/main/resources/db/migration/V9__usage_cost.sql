-- V9: đo chi phí LLM (Lát 1). Chỉ ALTER trên bảng V2 (an toàn khi DB đã có dữ liệu), không DROP dữ liệu.
-- Tiền = micro-USD (BIGINT). Không float ở bất kỳ đâu.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------- model_price
-- Giữ tên cột V2 (input_micro_per_mtok...). Chỉ thêm bản ghi mới; bản cũ chỉ được "đóng" bằng effective_to (trigger dưới).
ALTER TABLE model_price
    ADD COLUMN provider                    VARCHAR(50)  NOT NULL DEFAULT 'unknown',
    ADD COLUMN cached_input_micro_per_mtok BIGINT CHECK (cached_input_micro_per_mtok IS NULL OR cached_input_micro_per_mtok >= 0),
    ADD COLUMN effective_to                TIMESTAMPTZ,
    ADD COLUMN is_local                    BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN note                        VARCHAR(200);
-- dữ liệu V2 (nếu có): suy provider từ model_profile, đóng các bản cũ để không chồng khoảng hiệu lực
UPDATE model_price mp SET provider = pr.provider FROM model_profile pr WHERE pr.model = mp.model;
UPDATE model_price mp SET effective_to = nx.next_from
FROM (SELECT id, lead(effective_from) OVER (PARTITION BY provider, model ORDER BY effective_from) AS next_from FROM model_price) nx
WHERE nx.id = mp.id AND nx.next_from > mp.effective_from;
ALTER TABLE model_price ADD CONSTRAINT model_price_nonneg_chk CHECK (input_micro_per_mtok >= 0 AND output_micro_per_mtok >= 0);
ALTER TABLE model_price ADD CONSTRAINT model_price_range_chk CHECK (effective_to IS NULL OR effective_to > effective_from);
-- không chồng khoảng hiệu lực cho cùng (provider, model)
ALTER TABLE model_price ADD CONSTRAINT model_price_no_overlap
    EXCLUDE USING gist (provider WITH =, model WITH =, tstzrange(effective_from, effective_to) WITH &&);

CREATE FUNCTION model_price_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'model_price là bảng chỉ-thêm: không xoá';
    END IF;
    -- cho phép duy nhất: đóng bản ghi đang mở (effective_to NULL -> giá trị); mọi cột khác phải giữ nguyên
    IF OLD.effective_to IS NULL AND NEW.effective_to IS NOT NULL
       AND (NEW.id, NEW.provider, NEW.model, NEW.input_micro_per_mtok, NEW.output_micro_per_mtok,
            NEW.cached_input_micro_per_mtok, NEW.effective_from, NEW.is_local)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.provider, OLD.model, OLD.input_micro_per_mtok, OLD.output_micro_per_mtok,
            OLD.cached_input_micro_per_mtok, OLD.effective_from, OLD.is_local) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'model_price là bảng chỉ-thêm: không sửa giá cũ, hãy thêm bản ghi mới';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_model_price_append_only BEFORE UPDATE OR DELETE ON model_price
    FOR EACH ROW EXECUTE FUNCTION model_price_append_only();

-- Giá MẪU (không phải giá thật): đổi bằng PUT /api/model-prices. Model local cố ý giá 0 (is_local) nhưng vẫn đếm token.
INSERT INTO model_price (provider, model, input_micro_per_mtok, output_micro_per_mtok, effective_from, is_local, note)
SELECT v.* FROM (VALUES
    ('fake',      'fake',              0::bigint,        0::bigint, '2000-01-01T00:00:00Z'::timestamptz, TRUE,  'LLM giả cho dev/test'),
    ('ollama',    'qwen3:32b',         0,        0, '2000-01-01T00:00:00Z', TRUE,  'model local, điện không tính'),
    ('ollama',    'qwen3:4b',          0,        0, '2000-01-01T00:00:00Z', TRUE,  'model local, điện không tính'),
    ('anthropic', 'claude-opus-5-5',   15000000, 75000000, '2000-01-01T00:00:00Z', FALSE, 'GIÁ MẪU - chưa phải giá thật'),
    ('anthropic', 'claude-sonnet-5-5', 3000000,  15000000, '2000-01-01T00:00:00Z', FALSE, 'GIÁ MẪU - chưa phải giá thật'),
    ('anthropic', 'claude-haiku-5-5',  1000000,  5000000,  '2000-01-01T00:00:00Z', FALSE, 'GIÁ MẪU - chưa phải giá thật')
) AS v(provider, model, i, o, f, l, n)
WHERE NOT EXISTS (SELECT 1 FROM model_price m WHERE m.provider = v.provider AND m.model = v.model);

-- ---------------------------------------------------------------- usage_record
ALTER TABLE usage_record RENAME COLUMN input_tokens  TO prompt_tokens;
ALTER TABLE usage_record RENAME COLUMN output_tokens TO completion_tokens;
ALTER TABLE usage_record RENAME COLUMN created_at    TO occurred_at;
ALTER INDEX idx_usage_created RENAME TO idx_usage_occurred;
ALTER TABLE usage_record
    ADD COLUMN request_id    UUID         NOT NULL DEFAULT gen_random_uuid(),   -- bản ghi cũ nhận id ngẫu nhiên
    ADD COLUMN goal_id       UUID,
    ADD COLUMN project_id    UUID,
    ADD COLUMN department_id UUID,
    ADD COLUMN device_id     UUID,
    ADD COLUMN provider      VARCHAR(50)  NOT NULL DEFAULT 'unknown',
    ADD COLUMN purpose       VARCHAR(20)  NOT NULL DEFAULT 'AGENT_RUN',
    ADD COLUMN cached_tokens BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN estimated     BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN price_id      UUID REFERENCES model_price(id),
    ADD COLUMN priced        BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN latency_ms    BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN status        VARCHAR(10)  NOT NULL DEFAULT 'OK',
    ADD COLUMN error_code    VARCHAR(100);
ALTER TABLE usage_record ADD CONSTRAINT usage_record_request_uk UNIQUE (request_id);
ALTER TABLE usage_record ADD CONSTRAINT usage_record_status_chk CHECK (status IN ('OK', 'ERROR'));
ALTER TABLE usage_record ADD CONSTRAINT usage_record_nonneg_chk
    CHECK (prompt_tokens >= 0 AND completion_tokens >= 0 AND cached_tokens >= 0 AND cost_micro_usd >= 0 AND latency_ms >= 0);
CREATE INDEX idx_usage_agent_occurred   ON usage_record (agent_id, occurred_at);
CREATE INDEX idx_usage_project_occurred ON usage_record (project_id, occurred_at);
CREATE INDEX idx_usage_task             ON usage_record (task_id);
CREATE INDEX idx_usage_unpriced         ON usage_record (provider, model) WHERE NOT priced;

-- ---------------------------------------------------------------- usage_daily
-- agent_id/project_id NULL được thay bằng UUID "không có" (toàn số 0) để nằm được trong khoá chính.
ALTER TABLE usage_daily RENAME COLUMN input_tokens  TO prompt_tokens;
ALTER TABLE usage_daily RENAME COLUMN output_tokens TO completion_tokens;
ALTER TABLE usage_daily
    ADD COLUMN project_id    UUID   NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    ADD COLUMN department_id UUID,
    ADD COLUMN calls         BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN errors        BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN unpriced_calls BIGINT NOT NULL DEFAULT 0;
ALTER TABLE usage_daily DROP CONSTRAINT usage_daily_pkey;
ALTER TABLE usage_daily ADD PRIMARY KEY (day, agent_id, project_id, model);
CREATE INDEX idx_usage_daily_project ON usage_daily (project_id, day);

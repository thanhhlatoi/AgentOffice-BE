-- Công cụ và quyền cho FE (docs FE: BE_API_SPEC_PART3 / types/tools.ts)

-- Cờ quản trị cho tool MONEY/DESTRUCTIVE (tắt mặc định): tool chỉ dùng được khi cờ bật
ALTER TABLE tool ADD COLUMN admin_flag_on BOOLEAN NOT NULL DEFAULT FALSE;

-- Quyền chi tiết của một agent với một tool
ALTER TABLE agent_tool_grant
    ADD COLUMN limits         JSONB       NOT NULL DEFAULT '{}'::jsonb,   -- {maxCallsPerTask, maxPerMinute, expiresAt}
    ADD COLUMN always_approve BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN secret_id      UUID REFERENCES secret(id) ON DELETE SET NULL,
    ADD COLUMN created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at     TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE secret ADD COLUMN kind VARCHAR(40) NOT NULL DEFAULT 'generic';

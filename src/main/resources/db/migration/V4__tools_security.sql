-- Giai đoạn 4A/4B: tool, grant (deny-by-default), secret, ACL, audit, model profile, template
CREATE TABLE tool (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    risk_level  VARCHAR(20) NOT NULL,
    input_schema JSONB,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE
);

-- Không có dòng grant = không được dùng tool (deny-by-default).
CREATE TABLE agent_tool_grant (
    agent_id UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    tool_id  UUID NOT NULL REFERENCES tool(id)  ON DELETE CASCADE,
    scope    JSONB,                              -- vd {"allowedHosts":[...],"pathPrefix":"/data/x"}
    PRIMARY KEY (agent_id, tool_id)
);

CREATE TABLE secret (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL UNIQUE,
    ciphertext BYTEA NOT NULL,                   -- AES-GCM
    iv         BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE data_acl (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource    VARCHAR(200) NOT NULL,
    sensitivity VARCHAR(20)  NOT NULL DEFAULT 'INTERNAL',  -- PUBLIC|INTERNAL|CONFIDENTIAL
    agent_id    UUID REFERENCES agent(id)
);

CREATE TABLE tool_call_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id    UUID REFERENCES agent(id),
    task_id     UUID REFERENCES task(id),
    tool_name   VARCHAR(100) NOT NULL,
    args_redacted JSONB,
    outcome     VARCHAR(20) NOT NULL,            -- OK|DENIED|ERROR|WAITING_APPROVAL
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE permission_audit (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor      VARCHAR(100) NOT NULL,
    action     VARCHAR(60)  NOT NULL,
    detail     JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE model_profile (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(50) NOT NULL UNIQUE,
    provider      VARCHAR(50) NOT NULL,
    model         VARCHAR(100) NOT NULL,
    is_local      BOOLEAN NOT NULL DEFAULT FALSE,
    escalate_to   UUID REFERENCES model_profile(id)
);

CREATE TABLE agent_template (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100) NOT NULL UNIQUE,
    role          VARCHAR(100) NOT NULL,
    department    VARCHAR(50)  NOT NULL,
    system_prompt TEXT NOT NULL,
    default_tools JSONB,
    model_profile VARCHAR(50)
);

-- Giai đoạn 5: tầng, bố cục, bàn, họp, tuỳ chỉnh
CREATE TABLE floor (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL,
    level      INT NOT NULL UNIQUE,
    department VARCHAR(50)
);

CREATE TABLE office_layout (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    floor_id   UUID NOT NULL REFERENCES floor(id),
    version    INT NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'DRAFT',   -- DRAFT|PUBLISHED|HISTORY
    layout     JSONB NOT NULL,                         -- schema trong prompt-giai-doan-5.md
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (floor_id, version)
);

CREATE TABLE desk_assignment (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    floor_id  UUID NOT NULL REFERENCES floor(id),
    desk_key  VARCHAR(50) NOT NULL,
    agent_id  UUID REFERENCES agent(id),
    UNIQUE (floor_id, desk_key),
    UNIQUE (agent_id)
);

CREATE TABLE meeting (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    floor_id   UUID REFERENCES floor(id),
    room_key   VARCHAR(50),
    topic      VARCHAR(300),
    task_id    UUID REFERENCES task(id),
    starts_at  TIMESTAMPTZ,
    ends_at    TIMESTAMPTZ
);
CREATE TABLE meeting_attendee (
    meeting_id UUID NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
    agent_id   UUID NOT NULL REFERENCES agent(id),
    PRIMARY KEY (meeting_id, agent_id)
);

CREATE TABLE office_preferences (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sound_on   BOOLEAN NOT NULL DEFAULT FALSE,
    volume     INT NOT NULL DEFAULT 50,
    theme      VARCHAR(20) NOT NULL DEFAULT 'day'
);

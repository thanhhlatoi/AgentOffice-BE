-- V12: tên bot (profile) trên Hermes tương ứng với agent. NULL = agent chưa có bot (tạo khi Hermes admin chưa bật).
ALTER TABLE agent ADD COLUMN hermes_profile VARCHAR(64);
CREATE UNIQUE INDEX uq_agent_hermes_profile ON agent (hermes_profile) WHERE hermes_profile IS NOT NULL;

-- V11: dự án + Kanban (Lát 3). Chỉ ALTER/CREATE; chạy được trên DB đã có task.

-- ---------------------------------------------------------------- project
ALTER TABLE project
    ADD COLUMN department_id  UUID REFERENCES department(id),
    ADD COLUMN owner_agent_id UUID REFERENCES agent(id),
    ADD COLUMN color          VARCHAR(9)  NOT NULL DEFAULT '#4F46E5',
    ADD COLUMN deadline       TIMESTAMPTZ,
    ADD COLUMN progress_pct   INT         NOT NULL DEFAULT 0 CHECK (progress_pct BETWEEN 0 AND 100),
    ADD COLUMN has_plan       BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN finished_at    TIMESTAMPTZ;
ALTER TABLE project ADD CONSTRAINT project_status_chk CHECK (status IN ('ACTIVE','PAUSED','DONE','ARCHIVED'));
CREATE INDEX idx_goal_project ON goal (project_id);

CREATE TABLE project_progress_daily (
    project_id   UUID NOT NULL REFERENCES project(id),
    day          DATE NOT NULL,
    progress_pct INT  NOT NULL CHECK (progress_pct BETWEEN 0 AND 100),
    succeeded    INT  NOT NULL,
    failed       INT  NOT NULL,
    total        INT  NOT NULL,
    PRIMARY KEY (project_id, day)
);

-- ---------------------------------------------------------------- task
ALTER TABLE task
    ADD COLUMN project_id       UUID REFERENCES project(id),
    ADD COLUMN weight           INT    NOT NULL DEFAULT 1 CHECK (weight BETWEEN 1 AND 5),
    ADD COLUMN labels           TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN retry_of_task_id UUID REFERENCES task(id);
UPDATE task t SET project_id = g.project_id FROM goal g WHERE g.id = t.goal_id AND g.project_id IS NOT NULL;
CREATE INDEX idx_task_project ON task (project_id);
CREATE INDEX idx_task_retry_of ON task (retry_of_task_id);

-- kanban_rank: chuỗi so sánh THEO BYTE (COLLATE "C") để thứ tự khớp với LexoRank/String.compareTo, không phụ thuộc locale.
-- Task mới nhận số từ sequence (luôn ở cuối, không cần tính toán); chèn giữa hai thẻ do domain/task/LexoRank.between lo.
ALTER TABLE task ALTER COLUMN kanban_rank TYPE VARCHAR(64) COLLATE "C";
CREATE SEQUENCE task_rank_seq;
UPDATE task t SET kanban_rank = lpad(o.n::text, 20, '0')
FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS n FROM task) o
WHERE o.id = t.id;
SELECT setval('task_rank_seq', GREATEST((SELECT count(*) FROM task), 1));
CREATE FUNCTION task_default_rank() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.kanban_rank IS NULL THEN
        NEW.kanban_rank := lpad(nextval('task_rank_seq')::text, 20, '0');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_task_default_rank BEFORE INSERT ON task FOR EACH ROW EXECUTE FUNCTION task_default_rank();
ALTER TABLE task ALTER COLUMN kanban_rank SET NOT NULL;

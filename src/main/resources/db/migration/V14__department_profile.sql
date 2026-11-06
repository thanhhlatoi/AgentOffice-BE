-- V14: phòng ban có slug/icon/màu/đồ trang trí/trưởng phòng cho FE. Chỉ ADD COLUMN + backfill, không xoá dữ liệu.
ALTER TABLE department ADD COLUMN slug VARCHAR(60);
ALTER TABLE department ADD COLUMN icon VARCHAR(40) NOT NULL DEFAULT 'building';
ALTER TABLE department ADD COLUMN color VARCHAR(16);
ALTER TABLE department ADD COLUMN props TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE department ADD COLUMN monitors_per_desk INT NOT NULL DEFAULT 1 CHECK (monitors_per_desk BETWEEN 1 AND 3);
ALTER TABLE department ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
ALTER TABLE department ADD COLUMN head_agent_id UUID REFERENCES agent(id) ON DELETE SET NULL;

-- Phòng cũ (tên không dấu): slug = tên viết thường, ký tự lạ thành '-'
UPDATE department SET slug = trim(both '-' from regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g')) WHERE slug IS NULL;
UPDATE department d SET sort_order = r.n FROM (SELECT id, row_number() OVER (ORDER BY created_at, name) AS n FROM department) r WHERE r.id = d.id;
ALTER TABLE department ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX uq_department_slug ON department (slug);

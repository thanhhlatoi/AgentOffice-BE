-- V15: mọi nơi INSERT INTO department(name) (vd AgentCreatedListener) không biết đến slug. Postgres kiểm tra NOT NULL trước ON CONFLICT,
-- nên không có slug là lỗi dù phòng đã tồn tại. Trigger tự sinh slug từ tên khi thiếu (trùng thì thêm hậu tố).
CREATE FUNCTION department_default_slug() RETURNS trigger AS $$
DECLARE
    base TEXT;
    candidate TEXT;
    n INT := 1;
BEGIN
    IF NEW.slug IS NULL THEN
        base := trim(both '-' from regexp_replace(lower(NEW.name), '[^a-z0-9]+', '-', 'g'));
        IF base = '' THEN base := 'phong'; END IF;
        candidate := base;
        WHILE EXISTS (SELECT 1 FROM department WHERE slug = candidate) LOOP
            n := n + 1;
            candidate := base || '-' || n;
        END LOOP;
        NEW.slug := candidate;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_department_default_slug BEFORE INSERT ON department
    FOR EACH ROW EXECUTE FUNCTION department_default_slug();

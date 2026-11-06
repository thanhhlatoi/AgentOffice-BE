-- V13: thông tin hiển thị/cấu hình agent cho FE (system prompt, màu áo, kỹ năng). Chỉ ADD COLUMN, không đụng dữ liệu cũ.
ALTER TABLE agent ADD COLUMN system_prompt TEXT;
ALTER TABLE agent ADD COLUMN color VARCHAR(16);
ALTER TABLE agent ADD COLUMN skills TEXT[] NOT NULL DEFAULT '{}';

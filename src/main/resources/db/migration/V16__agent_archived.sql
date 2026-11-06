-- V16: "xoá" agent = lưu trữ (ẩn khỏi FE, OFFLINE, bot Hermes bị xoá). Giữ dòng agent để task/approval/chi phí cũ vẫn tham chiếu được.
ALTER TABLE agent ADD COLUMN archived_at TIMESTAMPTZ;

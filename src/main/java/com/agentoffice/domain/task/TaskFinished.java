package com.agentoffice.domain.task;

import java.util.UUID;

/**
 * Spring event nội bộ, phát ĐỒNG BỘ trong transaction đổi trạng thái khi task chạm trạng thái kết thúc.
 * Listener (mở khoá phụ thuộc, tổng hợp goal...) chạy cùng transaction => atomic với việc đổi status.
 */
public record TaskFinished(UUID taskId, UUID goalId, TaskStatus status) {}

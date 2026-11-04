package com.agentoffice.domain.task;

import java.util.UUID;

/** Spring event đồng bộ: tiến độ của dự án có thể đã đổi (task mới, đổi weight, chuyển goal sang dự án khác...). */
public record ProjectTouched(UUID projectId) {}

package com.agentoffice.support;

import org.junit.jupiter.api.Tag;

/**
 * Test cho các lớp LÕI do bạn tự viết (TaskStateMachine, TaskScheduler, PlanValidator, ApprovalGate).
 * Chủ ý Chạy phần còn lại: mvn verify -DexcludedGroups=core
 */
@Tag("core")
public abstract class CoreIT extends PostgresIT {}

package com.agentoffice.support;

import com.agentoffice.service.approval.ApprovalGate;
import com.agentoffice.domain.task.PlanValidator;
import com.agentoffice.service.task.TaskScheduler;
import com.agentoffice.domain.task.TaskStateMachine;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Test "ống nước" của tôi: các lớp lõi còn là stub nên được MOCK (state machine cho phép mọi chuyển, validator trả rỗng...).
 * Mục đích: chứng minh service/transaction/event/API đúng, KHÔNG kiểm logic lõi của bạn (đã có CoreIT lo).
 */
public abstract class PlumbingIT extends PostgresIT {
    @MockBean protected TaskStateMachine stateMachine;
    @MockBean protected TaskScheduler scheduler;
    @MockBean protected PlanValidator validator;
    @MockBean protected ApprovalGate gate;
}

package com.agentoffice.domain.approval;

/** Khi chủ từ chối/hết hạn một lời gọi tool: FAIL_TASK = task FAILED; RETURN_TO_AGENT = trả lý do cho agent (task -> RUNNING). */
public enum RejectMode { FAIL_TASK, RETURN_TO_AGENT }

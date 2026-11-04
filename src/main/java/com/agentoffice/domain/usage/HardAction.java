package com.agentoffice.domain.usage;

/** BLOCK: vượt hạn mức cứng thì chặn gọi LLM mới. WARN_ONLY: chỉ cảnh báo, vẫn cho gọi. */
public enum HardAction { BLOCK, WARN_ONLY }

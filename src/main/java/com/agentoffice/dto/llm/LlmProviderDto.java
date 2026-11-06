package com.agentoffice.dto.llm;

/** Nhà cung cấp đã kết nối trên gateway (tiền tố trước dấu / của model id, vd "cx", "ollama"). */
public record LlmProviderDto(String name, int modelCount) {}

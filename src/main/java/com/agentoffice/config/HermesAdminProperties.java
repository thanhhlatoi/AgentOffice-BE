package com.agentoffice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * agentoffice.hermes-admin.* : hermes_admin.py chạy trên máy Hermes (quản lý bot/profile). url hoặc key trống = tắt.
 * cloneFrom: bot mẫu để sao chép cấu hình/skill khi tạo bot cho agent mới (trống = tạo bot trống).
 */
@ConfigurationProperties("agentoffice.hermes-admin")
public record HermesAdminProperties(
        @DefaultValue("") String url,
        @DefaultValue("") String key,
        @DefaultValue("") String cloneFrom) {}

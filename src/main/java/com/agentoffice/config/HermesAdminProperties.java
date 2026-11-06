package com.agentoffice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * agentoffice.hermes-admin.* : hermes_admin.py chạy trên máy Hermes (quản lý bot/profile). url hoặc key trống = tắt.
 * cloneFrom: bot mẫu để sao chép cấu hình/skill khi tạo bot cho agent mới (trống = tạo bot trống). Nên là một bot mẫu riêng, sạch
 * (vd tpl_base), không phải bot đang chạy việc thật. Sau khi sao chép, BE ghi đè description/SOUL/model theo agent; skill và
 * model chỉnh riêng từng bot qua API. Plugin không do BE quản lý (chỉ đọc), người dùng tự cấu hình trên máy Hermes.
 */
@ConfigurationProperties("agentoffice.hermes-admin")
public record HermesAdminProperties(
        @DefaultValue("") String url,
        @DefaultValue("") String key,
        @DefaultValue("") String cloneFrom) {}

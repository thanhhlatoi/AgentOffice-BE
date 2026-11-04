package com.agentoffice.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** agentoffice.usage.* — múi giờ chốt "ngày" cho báo cáo; khoảng cách tối thiểu giữa 2 cảnh báo usage.unpriced của cùng model. */
@Validated
@ConfigurationProperties("agentoffice.usage")
public record UsageProperties(@DefaultValue("Asia/Ho_Chi_Minh") ZoneId reportZone,
                              @DefaultValue("1h") Duration unpricedAlertInterval) {}

package com.agentoffice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** agentoffice.storage.* — MinIO (S3). Giá trị lấy từ .env (MINIO_*) qua spring.config.import, xem application.yml. */
@Validated
@ConfigurationProperties("agentoffice.storage")
public record StorageProperties(@DefaultValue("http://localhost:9000") @NotBlank String endpoint,
                                @DefaultValue("") String accessKey,
                                @DefaultValue("") String secretKey,
                                @DefaultValue("agent-office") @NotBlank String bucket) {}

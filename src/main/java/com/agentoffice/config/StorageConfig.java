package com.agentoffice.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tạo client không mở kết nối: app vẫn khởi động được khi MinIO chưa chạy; lỗi chỉ xuất hiện lúc thật sự dùng. */
@Configuration
public class StorageConfig {
    @Bean
    public MinioClient minioClient(StorageProperties p) {
        return MinioClient.builder().endpoint(p.endpoint()).credentials(p.accessKey(), p.secretKey()).build();
    }
}

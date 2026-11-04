package com.agentoffice.client.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.config.StorageProperties;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** MinIO thật trong Testcontainers, không cần Spring. */
class ObjectStorageIT {
    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
            .withEnv("MINIO_ROOT_USER", "testuser").withEnv("MINIO_ROOT_PASSWORD", "testpassword")
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    static ObjectStorage storage;

    @BeforeAll static void start() {
        MINIO.start();
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        StorageProperties p = new StorageProperties(endpoint, "testuser", "testpassword", "test-bucket");
        storage = new ObjectStorage(MinioClient.builder().endpoint(endpoint).credentials("testuser", "testpassword").build(), p);
    }

    @AfterAll static void stop() { MINIO.stop(); }

    private static void put(String key, String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(b), b.length, "text/plain; charset=utf-8");
    }

    @Test void putThenGetReturnsTheSameBytesAndBucketIsCreatedOnFirstWrite() throws Exception {
        assertThat(storage.exists("reports/none.txt")).as("bucket chưa có cũng chỉ là 'không tồn tại'").isFalse();
        put("reports/2026/11/bao-cao.txt", "Báo cáo doanh thu Đà Nẵng");
        try (InputStream in = storage.get("reports/2026/11/bao-cao.txt")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("Báo cáo doanh thu Đà Nẵng");
        }
        assertThat(storage.exists("reports/2026/11/bao-cao.txt")).isTrue();
    }

    @Test void unknownSizeStreamsAreAccepted() throws Exception {
        storage.put("blob/unknown-size.bin", new ByteArrayInputStream(new byte[] {1, 2, 3}), -1, null);
        try (InputStream in = storage.get("blob/unknown-size.bin")) { assertThat(in.readAllBytes()).containsExactly(1, 2, 3); }
    }

    @Test void presignedLinkDownloadsWithoutCredentials() throws Exception {
        put("share/x.txt", "xin chào");
        String url = storage.presignedGetUrl("share/x.txt", Duration.ofMinutes(5));
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("xin chào");
        assertThatThrownBy(() -> storage.presignedGetUrl("share/x.txt", Duration.ofDays(8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.presignedGetUrl("share/x.txt", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void deleteIsIdempotent() {
        put("tmp/d.txt", "x");
        storage.delete("tmp/d.txt");
        storage.delete("tmp/d.txt");
        assertThat(storage.exists("tmp/d.txt")).isFalse();
        assertThatThrownBy(() -> storage.get("tmp/d.txt")).isInstanceOf(StorageException.class);
    }

    @Test void suspiciousKeysAreRejectedBeforeReachingMinio() {
        for (String bad : new String[] {"", " ", "/abs.txt", "a/../b.txt", "a\\b.txt", "x".repeat(1025)})
            assertThatThrownBy(() -> storage.exists(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.get(null)).isInstanceOf(IllegalArgumentException.class);
    }
}

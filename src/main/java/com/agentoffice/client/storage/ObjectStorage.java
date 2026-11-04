package com.agentoffice.client.storage;

import com.agentoffice.config.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.InputStream;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lưu file (báo cáo, file agent tạo, tài liệu kho tri thức...) trên MinIO, một bucket duy nhất, phân vùng bằng tiền tố key
 * (vd "reports/2026/11/x.pdf"). Bucket được tạo ở lần ghi đầu tiên, không phải lúc khởi động.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObjectStorage {
    /** MinIO/S3 không cho link ký sẵn sống quá 7 ngày. */
    static final Duration MAX_PRESIGN = Duration.ofDays(7);

    private final MinioClient minio;
    private final StorageProperties props;
    private volatile boolean bucketReady;

    /** size = -1 nếu không biết trước (SDK tự chia phần 10 MB). */
    public void put(String key, InputStream data, long size, String contentType) {
        checkKey(key);
        ensureBucket();
        call("ghi " + key, () -> minio.putObject(PutObjectArgs.builder().bucket(props.bucket()).object(key)
                .stream(data, size, size < 0 ? 10 * 1024 * 1024 : -1)
                .contentType(contentType == null ? "application/octet-stream" : contentType).build()));
    }

    /** Người gọi PHẢI đóng stream (try-with-resources). */
    public InputStream get(String key) {
        checkKey(key);
        return call("đọc " + key, () -> minio.getObject(GetObjectArgs.builder().bucket(props.bucket()).object(key).build()));
    }

    public boolean exists(String key) {
        checkKey(key);
        try {
            minio.statObject(StatObjectArgs.builder().bucket(props.bucket()).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) return false;
            throw new StorageException("Không kiểm tra được " + key, e);
        } catch (Exception e) {
            throw new StorageException("Không kiểm tra được " + key, e);
        }
    }

    /** Xoá không tồn tại cũng coi là thành công (idempotent, giống S3). */
    public void delete(String key) {
        checkKey(key);
        call("xoá " + key, () -> { minio.removeObject(RemoveObjectArgs.builder().bucket(props.bucket()).object(key).build()); return null; });
    }

    /** Link tải trực tiếp có hạn cho FE (không đi qua backend). ttl trong (0, 7 ngày]. */
    public String presignedGetUrl(String key, Duration ttl) {
        checkKey(key);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_PRESIGN) > 0)
            throw new IllegalArgumentException("ttl phải trong (0, 7 ngày]: " + ttl);
        return call("ký link " + key, () -> minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET).bucket(props.bucket()).object(key).expiry((int) ttl.toSeconds()).build()));
    }

    private void ensureBucket() {
        if (bucketReady) return;
        synchronized (this) {
            if (bucketReady) return;
            call("tạo bucket " + props.bucket(), () -> {
                if (!minio.bucketExists(BucketExistsArgs.builder().bucket(props.bucket()).build())) {
                    minio.makeBucket(MakeBucketArgs.builder().bucket(props.bucket()).build());
                    log.info("Đã tạo bucket MinIO {}", props.bucket());
                }
                return null;
            });
            bucketReady = true;
        }
    }

    /** Key là đầu vào có thể đến từ agent: chặn rỗng, '/' đầu, '..' và chuỗi quá dài để không thoát khỏi tiền tố được phép. */
    static void checkKey(String key) {
        if (key == null || key.isBlank() || key.length() > 1024 || key.startsWith("/") || key.contains("..") || key.contains("\\"))
            throw new IllegalArgumentException("Key không hợp lệ: " + key);
    }

    private interface Call<T> { T run() throws Exception; }

    private static <T> T call(String what, Call<T> c) {
        try {
            return c.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("MinIO: không " + what + " được", e);
        }
    }
}

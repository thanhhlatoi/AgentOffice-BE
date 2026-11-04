package com.agentoffice.client.storage;

/** Lỗi khi nói chuyện với MinIO (gói các checked exception của SDK). */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) { super(message, cause); }
}

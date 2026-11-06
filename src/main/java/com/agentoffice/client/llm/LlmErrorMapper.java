package com.agentoffice.client.llm;

import com.agentoffice.domain.usage.LlmCallException;
import java.io.IOException;

/**
 * LÕI BẠN TỰ VIẾT: đổi lỗi gọi gateway thành LlmCallException (retryable hay không). Spec: LlmErrorMapperTest (@Tag("core")).
 * Gợi ý: 429 và 5xx = tạm (retryable=true); 400/401/403/404 = vĩnh viễn (false). Còn lại (408? 402? 409? 413? 422? 3xx?) bạn quyết.
 * Mọi thông điệp chỉ nêu mã HTTP/loại lỗi, KHÔNG chèn body hay prompt (có thể chứa dữ liệu nhạy cảm).
 */
final class LlmErrorMapper {
    private LlmErrorMapper() {}

    /** Gateway trả mã không phải 2xx. */
    static LlmCallException fromStatus(int httpStatus) {
        // TODO: trả LlmCallException theo bảng gợi ý ở Javadoc
        throw new UnsupportedOperationException("TODO: LlmErrorMapper.fromStatus");
    }

    /** Không nhận được phản hồi: timeout (HttpTimeoutException), mất kết nối, DNS... (đều là IOException). */
    static LlmCallException fromTransport(IOException cause) {
        // TODO: gợi ý: timeout/IOException = tạm (retryable=true), nhớ truyền cause
        throw new UnsupportedOperationException("TODO: LlmErrorMapper.fromTransport");
    }
}

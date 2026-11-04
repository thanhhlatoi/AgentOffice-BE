package com.agentoffice.exception;

import com.agentoffice.config.web.CorrelationIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * MỘT chỗ duy nhất đổi exception thành HTTP. Mọi thân lỗi là ApiError{code,message,requestId,details?}.
 * Mức log: 4xx = INFO (lỗi do client/nghiệp vụ, có mã), 5xx = ERROR kèm stacktrace. Không bao giờ trả stacktrace hay nội dung lỗi nội bộ ra ngoài.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    ResponseEntity<Object> app(AppException e) {
        return respond(e.code(), e.getMessage(), e.details(), e);
    }

    /** Di sản: các IllegalStateException chưa chuyển sang AppException (vd TaskStateMachine.require) vẫn là 409 như cũ. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Object> illegalState(IllegalStateException e) {
        return respond(ErrorCode.CONFLICT, e.getMessage(), null, e);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> optimisticLock(OptimisticLockingFailureException e) {
        return respond(ErrorCode.OPTIMISTIC_LOCK, "Dữ liệu vừa bị thay đổi, hãy tải lại", null, e);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraint(ConstraintViolationException e) {
        return respond(ErrorCode.VALIDATION, "Yêu cầu không hợp lệ", null, e);
    }

    /** Lưới an toàn cuối: lỗi không lường trước => 500 chung chung, chi tiết CHỈ nằm ở log (tra bằng requestId). */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        return respond(ErrorCode.INTERNAL, "Lỗi nội bộ", null, e);
    }

    /** Mọi exception của Spring MVC (400/404/405/415...) đi qua đây để cũng ra ApiError. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        ErrorCode code = ErrorCode.forStatus(status);
        String msg = switch (ex) {
            case MethodArgumentNotValidException m -> m.getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage()).reduce((a, b) -> a + "; " + b).orElse("Yêu cầu không hợp lệ");
            default -> code == ErrorCode.VALIDATION ? "Yêu cầu không hợp lệ" : code.name();
        };
        log.info("{} {} ({})", code, msg, ex.getClass().getSimpleName());
        return ResponseEntity.status(status).headers(headers == null ? new HttpHeaders() : headers)
                .body(new ApiError(code.name(), msg, CorrelationIdFilter.current()));
    }

    private ResponseEntity<Object> respond(ErrorCode code, String message, java.util.Map<String, Object> details, Throwable cause) {
        if (code.status().is5xxServerError()) log.error("{} {}", code, message, cause);       // có stacktrace
        else log.info("{} {}", code, message);
        return ResponseEntity.status(code.status()).body(new ApiError(code.name(), message, CorrelationIdFilter.current(), details));
    }
}

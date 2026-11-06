package com.agentoffice.security;

import com.agentoffice.config.web.CorrelationIdFilter;
import com.agentoffice.exception.ApiError;
import com.agentoffice.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mọi request phải có khoá chung AGENTOFFICE_API_KEY ở header X-Api-Key (Authorization dành cho mã chủ, xem OwnerAuth).
 * Trình duyệt không đặt được header cho WebSocket nên riêng /ws nhận ?access_token=.
 * Chưa đặt khoá thì từ chối tất cả, không mở toang. Chỉ preflight CORS và /actuator/health được miễn.
 * ponytail: một khoá chung, không phân biệt người dùng; khoá nằm trong bundle FE nên ai mở được FE là lấy được.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Api-Key";
    private static final int MIN_KEY_LENGTH = 16;
    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final boolean enabled;
    private final byte[] key;
    private final ObjectMapper mapper;

    public ApiKeyFilter(@Value("${agentoffice.security.enabled:true}") boolean enabled,
                        @Value("${agentoffice.security.api-key:}") String key, ObjectMapper mapper) {
        String k = key == null ? "" : key.trim();
        this.enabled = enabled;
        this.key = k.length() < MIN_KEY_LENGTH ? new byte[0] : k.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
        if (!enabled) log.warn("agentoffice.security.enabled=false: API không có xác thực");
        else if (this.key.length == 0) log.error("Chưa đặt AGENTOFFICE_API_KEY (≥{} ký tự): mọi request sẽ bị từ chối", MIN_KEY_LENGTH);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return !enabled || CorsUtils.isPreFlightRequest(req) || path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String got = token(req);
        if (key.length > 0 && got != null && MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8), key)) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(ErrorCode.UNAUTHORIZED.status().value());
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(res.getOutputStream(), new ApiError(ErrorCode.UNAUTHORIZED.name(), "Thiếu hoặc sai khoá API", CorrelationIdFilter.current()));
    }

    private static String token(HttpServletRequest req) {
        String header = req.getHeader(HEADER);
        if (header != null) return header.trim();
        String path = req.getRequestURI();
        boolean webSocket = path.equals("/ws") || path.startsWith("/ws/");
        return webSocket && "GET".equals(req.getMethod()) ? req.getParameter("access_token") : null;
    }
}

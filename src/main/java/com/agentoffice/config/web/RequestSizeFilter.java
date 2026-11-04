package com.agentoffice.config.web;

import com.agentoffice.exception.ApiError;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.config.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Từ chối body quá lớn (413). ponytail: chỉ nhìn Content-Length; body chunked không khai báo độ dài chưa bị chặn ở đây. */
@Component
public class RequestSizeFilter extends OncePerRequestFilter {
    static final long MAX_BYTES = 256 * 1024;
    private final ObjectMapper mapper;

    public RequestSizeFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getContentLengthLong() > MAX_BYTES) {
            res.setStatus(413);
            res.setContentType("application/json");
            mapper.writeValue(res.getOutputStream(), new ApiError(ErrorCode.PAYLOAD_TOO_LARGE.name(), "Body vượt " + MAX_BYTES + " byte", CorrelationIdFilter.current()));
            return;
        }
        chain.doFilter(req, res);
    }
}

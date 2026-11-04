package com.agentoffice.service.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ghi bảng permission_audit (V4) cho hành động nhạy cảm: pause/resume, đổi approval policy. */
@Component
public class PermissionAuditLog {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PermissionAuditLog(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String action, Map<String, Object> detail) {
        try {
            jdbc.update("INSERT INTO permission_audit(actor, action, detail) VALUES (?, ?, ?::jsonb)",
                    actor, action, mapper.writeValueAsString(detail));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

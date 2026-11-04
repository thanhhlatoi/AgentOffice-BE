package com.agentoffice.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mỗi dòng log = 1 đối tượng JSON (profile prod). Không thêm thư viện: dùng Jackson có sẵn, escape đúng chuẩn. */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String doLayout(ILoggingEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", Instant.ofEpochMilli(e.getTimeStamp()).toString());
        m.put("level", e.getLevel().toString());
        m.put("logger", e.getLoggerName());
        m.put("thread", e.getThreadName());
        m.put("msg", e.getFormattedMessage());
        if (e.getMDCPropertyMap() != null && !e.getMDCPropertyMap().isEmpty()) m.putAll(e.getMDCPropertyMap());   // requestId, taskId, ...
        if (e.getThrowableProxy() != null) m.put("stack", ThrowableProxyUtil.asString(e.getThrowableProxy()));
        try {
            return MAPPER.writeValueAsString(m) + "\n";
        } catch (JsonProcessingException ex) {
            return "{\"level\":\"ERROR\",\"msg\":\"không serialize được dòng log\"}\n";
        }
    }
}

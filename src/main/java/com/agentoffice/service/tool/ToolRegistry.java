package com.agentoffice.service.tool;

import com.agentoffice.domain.tool.Tool;
import com.agentoffice.exception.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Đăng ký Tool theo tên. Spring tự nạp mọi bean Tool; test có thể register() thêm. */
@Component
public class ToolRegistry {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<Tool> beans) { beans.forEach(this::register); }

    public void register(Tool t) {
        if (tools.putIfAbsent(t.spec().name(), t) != null)
            throw new IllegalStateException("Tool trùng tên: " + t.spec().name());
    }

    public Optional<Tool> find(String name) { return Optional.ofNullable(tools.get(name)); }
    public Tool require(String name) {
        return find(name).orElseThrow(() -> new com.agentoffice.exception.NotFoundException("tool", name));
    }
}

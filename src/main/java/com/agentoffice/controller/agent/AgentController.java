package com.agentoffice.controller.agent;

import com.agentoffice.dto.agent.AgentDto;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.mapper.agent.AgentMapper;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.exception.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents")
public class AgentController {
    public record CreateAgent(@NotBlank String name, @NotBlank String role, @NotBlank String department) {}

    private final AgentRepository repo;
    private final AgentMapper mapper;

    public AgentController(AgentRepository repo, AgentMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @GetMapping
    public List<AgentDto> list() { return mapper.toDtos(repo.findAll()); }

    @GetMapping("/{id}")
    public AgentDto get(@PathVariable UUID id) {
        return repo.findById(id).map(mapper::toDto).orElseThrow(() -> new NotFoundException("agent", id));
    }

    @PostMapping
    public AgentDto create(@Valid @RequestBody CreateAgent r) {
        return mapper.toDto(repo.save(new Agent(r.name(), r.role(), r.department())));
    }
}

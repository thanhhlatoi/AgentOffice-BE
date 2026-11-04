package com.agentoffice.mapper.agent;

import com.agentoffice.dto.agent.AgentDto;
import com.agentoffice.entity.agent.Agent;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper
public interface AgentMapper {
    AgentDto toDto(Agent a);

    List<AgentDto> toDtos(List<Agent> agents);
}

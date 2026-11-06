package com.agentoffice.repository.agent;

import com.agentoffice.entity.agent.Agent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {
    /** Agent chưa bị xoá (lưu trữ). */
    java.util.List<Agent> findByArchivedAtIsNull(org.springframework.data.domain.Sort sort);
}

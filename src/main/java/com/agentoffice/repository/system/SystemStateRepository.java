package com.agentoffice.repository.system;

import com.agentoffice.entity.system.SystemState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemStateRepository extends JpaRepository<SystemState, String> {}

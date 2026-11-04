package com.agentoffice.config;

import com.agentoffice.domain.task.PlanValidator;
import com.agentoffice.domain.task.TaskStateMachine;
import com.agentoffice.domain.task.LexoRank;
import com.agentoffice.domain.usage.CostCalculator;
import com.agentoffice.domain.usage.ProgressCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lớp trong domain/ là Java thuần (không gắn @Component); Spring chỉ biết chúng qua các @Bean ở đây. */
@Configuration
public class DomainConfig {
    @Bean
    public TaskStateMachine taskStateMachine() { return new TaskStateMachine(); }

    @Bean
    public PlanValidator planValidator(LimitsProperties limits) { return new PlanValidator(limits); }

    @Bean
    public CostCalculator costCalculator() { return new CostCalculator(); }

    @Bean
    public ProgressCalculator progressCalculator() { return new ProgressCalculator(); }

    @Bean
    public LexoRank lexoRank() { return new LexoRank(); }
}

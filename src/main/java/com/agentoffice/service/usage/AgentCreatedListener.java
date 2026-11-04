package com.agentoffice.service.usage;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.agent.AgentCreated;
import jakarta.persistence.EntityManager;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Agent mới: gắn department (tạo nếu chưa có) và cấp ngân sách AGENT/DAY mặc định. Cả hai idempotent. */
@Component
public class AgentCreatedListener {
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final BudgetService budgets;

    public AgentCreatedListener(JdbcTemplate jdbc, EntityManager em, BudgetService budgets) {
        this.jdbc = jdbc;
        this.em = em;
        this.budgets = budgets;
    }

    @EventListener
    @Transactional
    public void onCreated(AgentCreated e) {
        em.flush();   // save() mới persist: phải có hàng agent trong DB thì UPDATE bên dưới mới trúng
        Agent a = e.agent();
        jdbc.update("INSERT INTO department(name) VALUES (?) ON CONFLICT (name) DO NOTHING", a.getDepartment());
        jdbc.update("UPDATE agent SET department_id = (SELECT id FROM department WHERE name = ?) WHERE id = ?", a.getDepartment(), a.getId());
        budgets.createDefaultAgentBudget(a.getId());
    }
}

package com.agentoffice.controller.task;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.service.task.TaskService;
import com.agentoffice.support.PostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP -> SQL thật cho Kanban: 5 cột, 200/409 kèm reasonCode, giao việc, weight/nhãn. */
@AutoConfigureMockMvc
class KanbanApiIT extends PostgresIT {
    @Autowired MockMvc mvc;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;

    private Task queued(Agent a, String title) {
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", title, "in", Priority.NORMAL, 0));
        return taskService.settle(t.getId());
    }

    private String body(String to, long version) { return "{\"toColumn\":\"" + to + "\",\"expectedVersion\":" + version + "}"; }

    @Test void boardHasFiveColumnsWithTotals() throws Exception {
        Agent a = agent("a", 5);
        queued(a, "một");
        queued(a, "hai");
        mvc.perform(get("/api/kanban").param("limit", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(5))
                .andExpect(jsonPath("$.columns[0].column").value("waiting")).andExpect(jsonPath("$.columns[0].total").value(2))
                .andExpect(jsonPath("$.columns[0].cards.length()").value(1)).andExpect(jsonPath("$.columns[0].nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.columns[0].cards[0].assigneeName").value("a"))
                .andExpect(jsonPath("$.columns[4].column").value("closed"));
        mvc.perform(get("/api/kanban").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/kanban").param("column", "bogus")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/kanban").param("q", "HAI")).andExpect(jsonPath("$.columns[0].total").value(1));
    }

    @Test void moveReturns200OrAConflictWithAReasonCode() throws Exception {
        Agent a = agent("a", 5);
        Task t = queued(a, "t");
        long v = tasks.findById(t.getId()).orElseThrow().getVersion();
        mvc.perform(post("/api/tasks/" + t.getId() + "/move").contentType(MediaType.APPLICATION_JSON).content(body("done", v)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT")).andExpect(jsonPath("$.details.reasonCode").value("NOT_ALLOWED"));
        mvc.perform(post("/api/tasks/" + t.getId() + "/move").contentType(MediaType.APPLICATION_JSON).content(body("running", v)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("PROMOTE")).andExpect(jsonPath("$.task.priority").value("HIGH"))
                .andExpect(jsonPath("$.task.version").value(v + 1));
        mvc.perform(post("/api/tasks/" + t.getId() + "/move").contentType(MediaType.APPLICATION_JSON).content(body("running", v)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.reasonCode").value("STALE_VERSION"));
        mvc.perform(post("/api/tasks/" + t.getId() + "/move").contentType(MediaType.APPLICATION_JSON).content(body("sideways", v + 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/" + t.getId() + "/move").contentType(MediaType.APPLICATION_JSON).content("{\"toColumn\":\"waiting\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/" + java.util.UUID.randomUUID() + "/move").contentType(MediaType.APPLICATION_JSON).content(body("waiting", 0)))
                .andExpect(status().isNotFound());
    }

    @Test void assignAndMeta() throws Exception {
        Agent a = agent("a", 5), b = agent("b", 5);
        Task t = queued(a, "t");
        long v = tasks.findById(t.getId()).orElseThrow().getVersion();
        mvc.perform(post("/api/tasks/" + t.getId() + "/assign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + b.getId() + "\",\"expectedVersion\":" + v + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assigneeAgentId").value(b.getId().toString()));
        mvc.perform(post("/api/tasks/" + t.getId() + "/assign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + a.getId() + "\",\"expectedVersion\":" + v + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.reasonCode").value("STALE_VERSION"));
        mvc.perform(patch("/api/tasks/" + t.getId() + "/meta").contentType(MediaType.APPLICATION_JSON).content("{\"weight\":4,\"labels\":[\"gấp\",\"q4\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.weight").value(4)).andExpect(jsonPath("$.labels[0]").value("gấp"));
        mvc.perform(patch("/api/tasks/" + t.getId() + "/meta").contentType(MediaType.APPLICATION_JSON).content("{\"weight\":6}")).andExpect(status().isBadRequest());
    }
}

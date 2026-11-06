package com.agentoffice.service.fe;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.domain.task.TaskStatus;
import org.junit.jupiter.api.Test;

class ViewServiceTest {
    @Test void everyBackendTaskStatusMapsToAFrontendOne() {
        var allowed = java.util.Set.of("QUEUED", "RUNNING", "AWAITING_APPROVAL", "WAITING_BUDGET", "DONE", "FAILED", "CANCELLED");
        for (TaskStatus s : TaskStatus.values()) assertThat(allowed).contains(ViewService.taskStatus(s));
        assertThat(ViewService.taskStatus(TaskStatus.SUCCEEDED)).isEqualTo("DONE");
        assertThat(ViewService.taskStatus(TaskStatus.BLOCKED)).isEqualTo("QUEUED");
        assertThat(ViewService.taskStatus(TaskStatus.WAITING_APPROVAL)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test void slugStripsVietnameseAccents() {
        assertThat(ViewService.slug("Công nghệ Web")).isEqualTo("cong-nghe-web");
        assertThat(ViewService.slug("Đội ngũ")).isEqualTo("doi-ngu");
        assertThat(ViewService.slug("!!!")).isEqualTo("phong");
    }
}

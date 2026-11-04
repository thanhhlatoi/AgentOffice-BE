package com.agentoffice.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.entity.agent.Agent;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AgentServiceTest {
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final UUID ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    private static Agent agent() throws Exception {
        Agent a = new Agent("x", "r", "d");
        Field f = Agent.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(a, ID);
        return a;
    }

    @Test
    void bo_dau_tieng_viet_va_them_hau_to_id() throws Exception {
        assertThat(AgentService.profileName("Nguyễn Đức Linh", agent())).isEqualTo("nguyen-duc-linh-3fa85f64");
    }

    @Test
    void ten_toan_ky_tu_la_van_ra_ten_hop_le() throws Exception {
        assertThat(AgentService.profileName("!!!", agent())).isEqualTo("agent-3fa85f64");
    }

    @Test
    void ten_dai_bi_cat_nhung_van_hop_le() throws Exception {
        String p = AgentService.profileName("a".repeat(200), agent());
        assertThat(p).matches(VALID).hasSizeLessThanOrEqualTo(64).endsWith("-3fa85f64");
    }
}

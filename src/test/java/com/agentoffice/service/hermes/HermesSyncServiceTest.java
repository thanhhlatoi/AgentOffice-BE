package com.agentoffice.service.hermes;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.config.HermesAdminProperties;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.exception.AppException;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.service.agent.AgentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HermesSyncServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HermesBotClient hermes = mock(HermesBotClient.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentService agentService = mock(AgentService.class);
    private final HermesSyncService service = new HermesSyncService(hermes, agents, agentService,
            new HermesAdminProperties("http://h", "k", "tpl_base"));
    private final Agent linked = new Agent("Linh", "Kế toán", "Tài chính");

    @BeforeEach
    void setUp() throws Exception {
        linked.setHermesProfile("linh-1");
        when(agents.findByArchivedAtIsNull(any())).thenReturn(List.of(linked));
        when(hermes.get(anyString())).thenReturn(JSON.readTree("{\"gateway\":\"unknown\"}"));
    }

    private void refusesDelete(String bot, String reason) {
        assertThatThrownBy(() -> service.deleteNamedBot(bot)).isInstanceOf(AppException.class).hasMessageContaining(reason);
        verify(hermes, never()).delete(anyString());
    }

    @Test void khongXoaDuocBotMau() { refusesDelete("tpl_base", "HERMES_CLONE_FROM"); }

    @Test void khongXoaDuocBotCuaAgent() { refusesDelete("linh-1", "Linh"); }

    @Test void khongXoaDuocBotDangChay() throws Exception {
        when(hermes.get("editor")).thenReturn(JSON.readTree("{\"gateway\":\"running\"}"));
        refusesDelete("editor", "đang chạy");
    }

    @Test void botRanhThiXoaDuoc() {
        service.deleteNamedBot("rac");
        verify(hermes).delete("rac");
    }

    @Test void botCuaAgentKhongSuaDuocSoulTrucTiep() throws Exception {
        JsonNode body = JSON.readTree("{\"soul\":\"x\"}");
        assertThatThrownBy(() -> service.updateNamedBot("linh-1", body)).isInstanceOf(AppException.class).hasMessageContaining("hồ sơ agent");
        verify(hermes, never()).update(anyString(), any());
    }

    @Test void botCuaAgentVanSuaDuocContextLength() throws Exception {
        JsonNode body = JSON.readTree("{\"contextLength\":32768}");
        service.updateNamedBot("linh-1", body);
        verify(hermes).update("linh-1", body);
    }

    @Test void botKhongThuocAgentSuaDuocSoul() throws Exception {
        JsonNode body = JSON.readTree("{\"soul\":\"x\"}");
        service.updateNamedBot("editor", body);
        verify(hermes).update("editor", body);
    }

    @Test void suaModelTrenBotThiGhiLaiVaoAgent() throws Exception {
        UUID id = UUID.randomUUID();
        when(agents.findById(id)).thenReturn(Optional.of(linked));
        service.updateBot(id, JSON.readTree("{\"model\":\"qwen3.6:35b-a3b-q4_K_M\"}"));
        verify(agentService).recordBotModel(id, "qwen3.6:35b-a3b-q4_K_M");
    }

    @Test void suaThongSoKhacThiKhongDungToiModelCuaAgent() throws Exception {
        UUID id = UUID.randomUUID();
        when(agents.findById(id)).thenReturn(Optional.of(linked));
        service.updateBot(id, JSON.readTree("{\"contextLength\":32768}"));
        verify(agentService, never()).recordBotModel(any(), anyString());
    }
}

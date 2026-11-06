package com.agentoffice.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.config.HermesAdminProperties;
import com.agentoffice.config.LocalLlmProperties;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.agent.AgentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Thứ tự giữa DB và Hermes: lỗi ở bước nào cũng không làm mất dữ liệu hay để DB lệch Hermes. */
class AgentServiceBotTest {
    private static final String BOT = "linh-3fa85f64";
    private final UUID id = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private final AgentRepository repo = mock(AgentRepository.class);
    private final HermesBotClient hermes = mock(HermesBotClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentService service = new AgentService(repo, hermes,
            new HermesAdminProperties("http://h", "k", "tpl_base"), new ObjectMapper(), jdbc,
            new LocalLlmProperties("http://x/v1", "", "ollama", "qwen3.6-64k:latest",
                    Map.of("local.small", "qwen3.6-64k:latest"), Duration.ofSeconds(5), ""),
            new TransactionTemplate(mock(PlatformTransactionManager.class)));
    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = new Agent("Linh", "Kế toán", "Tài chính");
        ReflectionTestUtils.setField(agent, "id", id);
        agent.setHermesProfile(BOT);
        agent.setModelProfile("local.small");
        when(hermes.configured()).thenReturn(true);
        when(repo.findById(id)).thenReturn(Optional.of(agent));
        when(repo.save(any())).thenAnswer(i -> {
            Agent a = i.getArgument(0);
            if (a.getId() == null) ReflectionTestUtils.setField(a, "id", id);
            return a;
        });
    }

    private static AgentService.Profile profile(String model, String color) {
        return new AgentService.Profile(null, model, color, null);
    }

    private JsonNode pushed() {
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(hermes).update(eq(BOT), body.capture());
        return body.getValue();
    }

    @Test void hermesLoiKhiTaoAgentThiAgentVanConVaChuaCoBot() {
        when(repo.findById(id)).thenReturn(Optional.empty());
        when(hermes.create(any())).thenThrow(new AppException(ErrorCode.HERMES_UNAVAILABLE, "mất mạng"));
        Agent a = service.create("Linh", "Kế toán", "Tài chính", profile("local.small", null));
        assertThat(a.getId()).isEqualTo(id);
        assertThat(a.getHermesProfile()).isNull();
    }

    @Test void taoAgentThanhCongThiGanBot() {
        when(repo.findById(id)).thenAnswer(i -> Optional.of(agent));
        Agent a = service.create("Linh", "Kế toán", "Tài chính", profile("local.small", null));
        assertThat(a.getHermesProfile()).isEqualTo(BOT);
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(hermes).create(body.capture());
        assertThat(body.getValue().path("cloneFrom").asText()).isEqualTo("tpl_base");
    }

    @Test void modelKhongChayDuocTrenBotBiTuChoiTruocKhiLuu() {
        assertThatThrownBy(() -> service.create("Linh", "Kế toán", "Tài chính", profile("claude-fable-5-1", null)))
                .isInstanceOf(AppException.class).hasMessageContaining("claude-fable-5-1");
        verify(repo, never()).save(any());
    }

    @Test void giuNguyenModelCuKhongChayDuocThiVanSuaDuocHoSo() {
        agent.setModelProfile("claude-fable-5-1");
        service.update(id, null, null, null, profile("claude-fable-5-1", "#111111"));
        assertThat(agent.getColor()).isEqualTo("#111111");
        verify(hermes, never()).update(anyString(), any());
    }

    @Test void chiDoiMauThiKhongDungToiBot() {
        service.update(id, null, null, null, profile("local.small", "#111111"));
        verify(hermes, never()).update(anyString(), any());
        assertThat(agent.getColor()).isEqualTo("#111111");
    }

    @Test void doiModelThiChiGhiModelSangBot() {
        service.update(id, null, null, null, profile("qwen3.6:35b-a3b-q4_K_M", null));
        JsonNode body = pushed();
        assertThat(body.path("model").asText()).isEqualTo("qwen3.6:35b-a3b-q4_K_M");
        assertThat(body.has("soul")).isFalse();
        assertThat(body.has("description")).isFalse();
    }

    @Test void doiVaiTroThiGhiMoTaVaSoulKhongGhiModel() {
        service.update(id, null, "Kiểm toán", null, null);
        JsonNode body = pushed();
        assertThat(body.path("description").asText()).isEqualTo("Kiểm toán - Tài chính");
        assertThat(body.has("soul")).isTrue();
        assertThat(body.has("model")).isFalse();
    }

    @Test void hermesLoiKhiSuaThiDbChuaDoi() {
        when(hermes.update(anyString(), any())).thenThrow(new AppException(ErrorCode.HERMES_UNAVAILABLE, "mất mạng"));
        assertThatThrownBy(() -> service.update(id, null, "Kiểm toán", null, null)).isInstanceOf(AppException.class);
        assertThat(agent.getRole()).isEqualTo("Kế toán");
        verify(repo, never()).save(any());
    }

    @Test void botDaMatThiVanLuuHoSo() {
        when(hermes.update(anyString(), any())).thenThrow(new NotFoundException("bot Hermes", BOT));
        service.update(id, null, "Kiểm toán", null, null);
        assertThat(agent.getRole()).isEqualTo("Kiểm toán");
    }

    @Test void xoaAgentLuuDbTruocRoiMoiXoaBot() {
        assertThat(service.archive(id)).isEqualTo(BOT);
        InOrder order = inOrder(repo, hermes);
        order.verify(repo).save(agent);
        order.verify(hermes).delete(BOT);
        assertThat(agent.getArchivedAt()).isNotNull();
    }

    @Test void dbLoiKhiXoaAgentThiKhongXoaBot() {
        doThrow(new IllegalStateException("db")).when(repo).save(any());
        assertThatThrownBy(() -> service.archive(id)).isInstanceOf(IllegalStateException.class);
        verify(hermes, never()).delete(anyString());
    }

    @Test void hermesLoiKhiXoaBotThiAgentVanDuocLuuTru() {
        doThrow(new AppException(ErrorCode.HERMES_UNAVAILABLE, "mất mạng")).when(hermes).delete(BOT);
        assertThat(service.archive(id)).isEqualTo(BOT);
        assertThat(agent.getArchivedAt()).isNotNull();
    }

    @Test void botDaTonTaiTuLanTruocThiNhanLaiKhongKet() {
        agent.setHermesProfile(null);
        when(hermes.create(any())).thenThrow(new AppException(ErrorCode.CONFLICT, "Da co bot"));
        assertThat(service.ensureBot(id)).isTrue();
        assertThat(agent.getHermesProfile()).isEqualTo(BOT);
        assertThat(pushed().has("soul")).isTrue();
    }

    @Test void botConSongThiEnsureBotKhongTaoMoi() {
        assertThat(service.ensureBot(id)).isFalse();
        verify(hermes, never()).create(any());
    }

    @Test void matBotMauThiBaoRoTenBien() {
        agent.setHermesProfile(null);
        when(hermes.create(any())).thenThrow(new NotFoundException("bot Hermes", "tpl_base"));
        assertThatThrownBy(() -> service.ensureBot(id)).isInstanceOf(AppException.class).hasMessageContaining("HERMES_CLONE_FROM");
    }

    @Test void ghiNhanModelSuaTrucTiepTrenBot() {
        service.recordBotModel(id, "qwen3.6:35b-a3b-q4_K_M");
        assertThat(agent.getModelProfile()).isEqualTo("qwen3.6:35b-a3b-q4_K_M");
        service.update(id, null, null, null, profile("qwen3.6:35b-a3b-q4_K_M", "#222222"));
        verify(hermes, never()).update(anyString(), any());
        assertThat(List.of(agent.getColor())).containsExactly("#222222");
    }
}

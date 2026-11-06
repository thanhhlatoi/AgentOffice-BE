package com.agentoffice.controller.hermes;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentoffice.service.hermes.HermesSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HermesSyncController.class)
class HermesChannelsControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean HermesSyncService service;

    private final UUID id = UUID.randomUUID();
    private static final String J = MediaType.APPLICATION_JSON_VALUE;

    private String path(String tail) { return "/api/fe/agents/" + id + "/hermes/channels" + tail; }

    @Test void danhSachKenh() throws Exception {
        when(service.channels(id)).thenReturn(json.readTree("{\"channels\":[{\"name\":\"NT 8\"}]}"));
        mvc.perform(get(path(""))).andExpect(status().isOk()).andExpect(jsonPath("$.channels[0].name").value("NT 8"));
    }

    @Test void moKenhChuyenDungTenVaMay() throws Exception {
        when(service.channelAction(id, "open", "Reddit NT 8", "ubu")).thenReturn(json.readTree("{\"status\":\"RUNNING\"}"));
        mvc.perform(post(path("/open")).contentType(J).content("{\"name\":\"Reddit NT 8\",\"machine\":\"ubu\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test void dongKenhKhongCanTenMay() throws Exception {
        when(service.channelAction(id, "close", "Reddit NT 8", null)).thenReturn(json.readTree("{\"status\":\"STOPPED\"}"));
        mvc.perform(post(path("/close")).contentType(J).content("{\"name\":\"Reddit NT 8\"}")).andExpect(status().isOk());
    }

    @Test void duLieuSaiBiChanTruocKhiToiHermes() throws Exception {
        for (String body : new String[] {"{}", "{\"name\":\"  \"}", "{\"name\":1}", "{\"name\":\"x\",\"machine\":\"../etc\"}", "{\"name\":\"" + "a".repeat(121) + "\"}"}) {
            mvc.perform(post(path("/open")).contentType(J).content(body)).andExpect(status().isBadRequest());
        }
        verify(service, never()).channelAction(any(), any(), any(), any());
    }

    @Test void khongCoDuongDangVideo() throws Exception {
        mvc.perform(post(path("/publish")).contentType(J).content("{\"name\":\"x\"}")).andExpect(status().is4xxClientError());
        verify(service, never()).channelAction(any(), any(), any(), any());
    }
}

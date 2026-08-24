package com.viralground.backend.controller;

import com.viralground.backend.notification.StagingEmailValidationTemplate;
import com.viralground.backend.service.StagingEmailValidationProbeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StagingEmailValidationControllerSecurityTest {

    private static final String PATH = "/admin/email-validation/probes";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StagingEmailValidationProbeService probeService;

    @Test
    void adminAndCsrfAreBothRequired() throws Exception {
        String request = "{\"template\":\"CONTACT_RECEIVED_ADMIN\"}";

        mockMvc.perform(post(PATH).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).with(user("creator").roles("CREATOR")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());

        verify(probeService, never()).queue(
                StagingEmailValidationTemplate.CONTACT_RECEIVED_ADMIN);
    }

    @Test
    void adminWithCsrfCanQueueOnlyEnumAndReceivesNoMessageData() throws Exception {
        mockMvc.perform(post(PATH).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"template\":\"CONTACT_RECEIVED_ADMIN\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.recipient").doesNotExist())
                .andExpect(jsonPath("$.body").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());

        verify(probeService).queue(
                StagingEmailValidationTemplate.CONTACT_RECEIVED_ADMIN);
    }

    @Test
    void unknownOrFreeFormInputIsRejected() throws Exception {
        mockMvc.perform(post(PATH).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"template\":\"CONTACT_RECEIVED_ADMIN\","
                                + "\"recipient\":\"outside@example.test\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(PATH).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"template\":\"FREE_FORM\"}"))
                .andExpect(status().isBadRequest());

        verify(probeService, never()).queue(
                org.mockito.ArgumentMatchers.any());
    }
}

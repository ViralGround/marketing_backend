package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.entity.Role;
import com.viralground.backend.service.InstagramConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CreatorInstagramControllerTest {

    @Test
    void featureKillSwitchDoesNotBlockUserRevocation() {
        InstagramConnectionService service = mock(InstagramConnectionService.class);
        CreatorInstagramController controller = new CreatorInstagramController(service);
        ReflectionTestUtils.setField(controller, "instagramFeatureEnabled", false);
        AuthUser creator = new AuthUser(7, "creator@example.test", Role.CREATOR, "Creator");

        var response = controller.disconnect(creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).disconnect(7);
    }
}

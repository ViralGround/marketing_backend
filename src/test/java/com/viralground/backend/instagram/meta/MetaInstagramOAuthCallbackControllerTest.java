package com.viralground.backend.instagram.meta;

import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.service.InstagramConnectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MetaInstagramOAuthCallbackControllerTest {

    private final InstagramConnectionService service = mock(InstagramConnectionService.class);
    private final MetaInstagramProperties properties = new MetaInstagramProperties(
            "app", "secret", "https://api.example/callback", "https://web.example/creator/mypage",
            "key", "verify", "v25.0", null, null, null, List.of(), Duration.ofMinutes(10),
            Duration.ofSeconds(3), Duration.ofSeconds(8), Duration.ZERO, Duration.ofDays(7), 3, 50, 3, 14);
    private final MetaInstagramOAuthCallbackController controller =
            new MetaInstagramOAuthCallbackController(service, properties);

    @BeforeEach
    void enableFeatureForRedirectTests() {
        ReflectionTestUtils.setField(controller, "instagramFeatureEnabled", true);
    }

    @Test
    void disabledFeatureReturns503WithoutRedirectOrServiceCall() {
        ReflectionTestUtils.setField(controller, "instagramFeatureEnabled", false);

        var response = controller.callback("secret-code", "secret-state", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getLocation()).isNull();
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void successRedirectsWithoutCodeOrState() {
        var response = controller.callback("secret-code", "secret-state", null);

        verify(service).completeAuthorization("secret-state", "secret-code");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("https://web.example/creator/mypage?instagram=connected")
                .doesNotContain("secret-code", "secret-state");
    }

    @Test
    void invalidStateRedirectsWithStablePublicReason() {
        doThrow(new InstagramIntegrationException("INSTAGRAM_INVALID_STATE", "internal detail",
                HttpStatus.BAD_REQUEST)).when(service).completeAuthorization("state", "code");

        var response = controller.callback("code", "state", null);

        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("https://web.example/creator/mypage?instagram=error&reason=invalid_state")
                .doesNotContain("internal");
    }

    @Test
    void userCancellationConsumesStateAndRedirectsCancelled() {
        var response = controller.callback(null, "state", "access_denied");
        verify(service).cancelAuthorization("state");
        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("https://web.example/creator/mypage?instagram=cancelled");
    }
}

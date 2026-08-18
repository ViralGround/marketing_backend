package com.viralground.backend.instagram.meta;

import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.webhook.InstagramWebhookService;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.DelegatingServletInputStream;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetaInstagramWebhookControllerTest {
    private final InstagramWebhookService service = mock(InstagramWebhookService.class);
    private final MetaInstagramWebhookController controller =
            new MetaInstagramWebhookController(service);

    @Test
    void rejectsDeclaredOversizeBeforeOpeningRequestStream() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong())
                .thenReturn((long) MetaInstagramWebhookController.MAX_WEBHOOK_PAYLOAD_BYTES + 1);

        assertThatThrownBy(() -> controller.receive(request, "sha256=signature"))
                .isInstanceOf(InstagramIntegrationException.class)
                .satisfies(error -> {
                    InstagramIntegrationException integrationError =
                            (InstagramIntegrationException) error;
                    assertThat(integrationError.getCode())
                            .isEqualTo("INSTAGRAM_WEBHOOK_PAYLOAD_TOO_LARGE");
                    assertThat(integrationError.getStatus().value()).isEqualTo(413);
                });
        verify(request, never()).getInputStream();
        verify(service, never()).accept(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsChunkedOversizeAfterReadingAtMostMaxPlusOne() throws Exception {
        HttpServletRequest request = requestWithUnknownLength(
                new byte[MetaInstagramWebhookController.MAX_WEBHOOK_PAYLOAD_BYTES + 50]);

        assertThatThrownBy(() -> controller.receive(request, "sha256=signature"))
                .isInstanceOf(InstagramIntegrationException.class)
                .extracting("code")
                .isEqualTo("INSTAGRAM_WEBHOOK_PAYLOAD_TOO_LARGE");
        verify(service, never()).accept(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preservesExactRawBytesForSignatureVerification() throws Exception {
        byte[] rawPayload = new byte[]{'{', '"', 'x', '"', ':', (byte) 0xC3, (byte) 0xA9, '}'};
        HttpServletRequest request = requestWithUnknownLength(rawPayload);

        var response = controller.receive(request, "sha256=signature");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(service).accept(rawPayload, "sha256=signature");
    }

    private HttpServletRequest requestWithUnknownLength(byte[] body) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletInputStream stream = new DelegatingServletInputStream(new ByteArrayInputStream(body));
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getInputStream()).thenReturn(stream);
        return request;
    }
}

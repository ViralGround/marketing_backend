package com.viralground.backend.instagram.webhook;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.meta.MetaInstagramProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InstagramWebhookServiceTest {

    private final MetaInstagramWebhookVerifier verifier = mock(MetaInstagramWebhookVerifier.class);
    private final InstagramWebhookDeliveryRepository repository = mock(InstagramWebhookDeliveryRepository.class);
    private final MetaInstagramProperties properties = new MetaInstagramProperties(
            "app", "secret", "https://api.example/callback", "https://web.example/result",
            "key", "verify", "v25.0", null, null, null, List.of(), Duration.ofMinutes(10),
            Duration.ofSeconds(3), Duration.ofSeconds(8), Duration.ZERO, Duration.ofDays(7), 3, 50, 3, 14);
    private final InstagramWebhookService service = new InstagramWebhookService(
            verifier, repository, properties, new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-08-13T02:00:00Z"), ZoneOffset.UTC),
            mock(PreproductionScheduledMutationGuard.class));

    @BeforeEach
    void enableInstagram() {
        ReflectionTestUtils.setField(service, "instagramFeatureEnabled", true);
    }

    @Test
    void storesOnlyDedupeHashForSignedPayload() {
        byte[] payload = "{\"object\":\"instagram\",\"entry\":[{\"id\":\"1789\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        when(verifier.validSignature(payload, "sha256=valid")).thenReturn(true);

        assertThat(service.accept(payload, "sha256=valid"))
                .isEqualTo(InstagramWebhookService.Acceptance.ACCEPTED);
        org.mockito.ArgumentCaptor<InstagramWebhookDelivery> saved =
                org.mockito.ArgumentCaptor.forClass(InstagramWebhookDelivery.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEntryCount()).isEqualTo(1);
        assertThat(saved.getValue().getEventHash()).hasSize(64).doesNotContain("1789");
    }

    @Test
    void duplicatePayloadIsIdempotent() {
        byte[] payload = "{\"object\":\"instagram\",\"entry\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        when(verifier.validSignature(payload, "sha256=valid")).thenReturn(true);
        when(repository.existsByEventHash(any())).thenReturn(true);

        assertThat(service.accept(payload, "sha256=valid"))
                .isEqualTo(InstagramWebhookService.Acceptance.DUPLICATE);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void invalidSignatureIsRejectedBeforeParsingOrPersistence() {
        byte[] payload = "not-json".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.accept(payload, "sha256=invalid"))
                .isInstanceOf(InstagramIntegrationException.class)
                .extracting("code").isEqualTo("INSTAGRAM_WEBHOOK_SIGNATURE_INVALID");
        verify(repository, never()).existsByEventHash(any());
    }

    @Test
    void disabledFeatureRejectsWebhookWith503BeforeSignatureOrPersistence() {
        ReflectionTestUtils.setField(service, "instagramFeatureEnabled", false);

        assertThatThrownBy(() -> service.accept("{}".getBytes(StandardCharsets.UTF_8), "sha256=value"))
                .isInstanceOf(InstagramIntegrationException.class)
                .satisfies(error -> assertThat(((InstagramIntegrationException) error).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(verifier, repository);
    }
}

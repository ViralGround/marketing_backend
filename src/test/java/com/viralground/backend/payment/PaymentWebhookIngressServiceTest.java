package com.viralground.backend.payment;

import com.viralground.backend.entity.PaymentWebhookEvent;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.PaymentWebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookIngressServiceTest {

    @Mock PaymentWebhookEventRepository repository;
    @Mock PaymentWebhookVerifier verifier;
    PaymentWebhookIngressService service;
    byte[] payload;

    @BeforeEach
    void setUp() {
        when(verifier.providerName()).thenReturn("provider");
        service = new PaymentWebhookIngressService(repository, List.of(verifier));
        payload = "{\"id\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void verifiesRawPayloadAndStoresHashWithoutRawBody() {
        when(verifier.verify(payload, Map.of("signature", "valid"))).thenReturn(verified());
        when(repository.findByProviderAndProviderEventId("provider", "evt-1"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.accept("Provider", payload, Map.of("signature", "valid"));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.event().getPayloadSha256()).hasSize(64);
        assertThat(result.event().getPayloadSha256()).doesNotContain("evt-1");
        verify(verifier).verify(payload, Map.of("signature", "valid"));
    }

    @Test
    void exactDuplicateIsAcknowledgedWithoutSavingAgain() {
        when(verifier.verify(payload, Map.of())).thenReturn(verified());
        // Avoid coupling to a hard-coded digest generated outside this test: capture from first pass.
        when(repository.findByProviderAndProviderEventId("provider", "evt-1"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        PaymentWebhookEvent first = service.accept("provider", payload, Map.of()).event();

        reset(repository);
        when(repository.findByProviderAndProviderEventId("provider", "evt-1"))
                .thenReturn(Optional.of(first));
        var replay = service.accept("provider", payload, Map.of());

        assertThat(replay.duplicate()).isTrue();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void sameEventIdWithDifferentPayloadIsRejectedAsTampering() {
        when(verifier.verify(payload, Map.of())).thenReturn(verified());
        when(repository.findByProviderAndProviderEventId("provider", "evt-1"))
                .thenReturn(Optional.of(existing("0000000000000000000000000000000000000000000000000000000000000000")));

        assertThatThrownBy(() -> service.accept("provider", payload, Map.of()))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void missingVerifierFailsBeforePersistence() {
        assertThatThrownBy(() -> service.accept("unknown", payload, Map.of()))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PAYMENT_WEBHOOK);
        verifyNoInteractions(repository);
    }

    private static PaymentWebhookVerifier.VerifiedWebhook verified() {
        return new PaymentWebhookVerifier.VerifiedWebhook(
                "evt-1", "payment.succeeded", "payment-1", LocalDateTime.of(2026, 8, 13, 12, 0));
    }

    private static PaymentWebhookEvent existing(String hash) {
        return PaymentWebhookEvent.builder()
                .provider("provider").providerEventId("evt-1")
                .eventType("payment.succeeded").payloadSha256(hash).build();
    }
}

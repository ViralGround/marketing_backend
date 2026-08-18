package com.viralground.backend.payment;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 상용 공급자마다 구현해야 하는 webhook 서명 검증 경계.
 * 구현체는 반드시 raw body로 서명을 검증하고 timestamp replay window도 확인해야 한다.
 */
public interface PaymentWebhookVerifier {

    String providerName();

    VerifiedWebhook verify(byte[] rawBody, Map<String, String> headers);

    record VerifiedWebhook(
            String providerEventId,
            String eventType,
            String providerObjectId,
            LocalDateTime providerOccurredAt
    ) {}
}

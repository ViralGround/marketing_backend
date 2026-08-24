package com.viralground.backend.payment;

import com.viralground.backend.entity.PaymentWebhookEvent;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.PaymentWebhookEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 상용 webhook controller가 호출할 공통 수신 계층이다. 아직 공급자/공개 URL을
 * 사용자가 결정하지 않았으므로 외부 endpoint 자체는 의도적으로 열지 않는다.
 */
@Service
@Slf4j
public class PaymentWebhookIngressService {

    private final PaymentWebhookEventRepository repository;
    private final Map<String, PaymentWebhookVerifier> verifiers;

    @Value("${features.payments.enabled:false}")
    private boolean paymentsFeatureEnabled = false;

    public PaymentWebhookIngressService(PaymentWebhookEventRepository repository,
                                        List<PaymentWebhookVerifier> verifiers) {
        this.repository = repository;
        this.verifiers = verifiers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                v -> normalizeProvider(v.providerName()), v -> v));
    }

    public IngressResult accept(String provider, byte[] rawBody, Map<String, String> headers) {
        if (!paymentsFeatureEnabled) {
            throw new AppException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
        String normalizedProvider = normalizeProvider(provider);
        PaymentWebhookVerifier verifier = verifiers.get(normalizedProvider);
        if (verifier == null) {
            log.warn("event=payment_webhook_rejected provider={} reason=no_verifier", normalizedProvider);
            throw new AppException(ErrorCode.INVALID_PAYMENT_WEBHOOK);
        }
        if (rawBody == null || rawBody.length == 0) {
            throw new AppException(ErrorCode.INVALID_PAYMENT_WEBHOOK);
        }

        PaymentWebhookVerifier.VerifiedWebhook verified = verifier.verify(
                rawBody, headers == null ? Map.of() : Map.copyOf(headers));
        validate(verified);
        String payloadHash = sha256(rawBody);

        var existing = repository.findByProviderAndProviderEventId(
                normalizedProvider, verified.providerEventId());
        if (existing.isPresent()) {
            if (!existing.get().getPayloadSha256().equals(payloadHash)) {
                log.error("event=payment_webhook_tamper provider={} providerEventId={} reason=payload_hash_mismatch",
                        normalizedProvider, verified.providerEventId());
                throw new AppException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
            }
            log.info("event=payment_webhook_duplicate provider={} providerEventId={}",
                    normalizedProvider, verified.providerEventId());
            return new IngressResult(existing.get(), true);
        }

        try {
            PaymentWebhookEvent saved = repository.saveAndFlush(PaymentWebhookEvent.builder()
                    .provider(normalizedProvider)
                    .providerEventId(verified.providerEventId())
                    .eventType(verified.eventType())
                    .providerObjectId(verified.providerObjectId())
                    .providerOccurredAt(verified.providerOccurredAt())
                    .payloadSha256(payloadHash)
                    .build());
            log.info("event=payment_webhook_accepted provider={} providerEventId={} eventType={} payloadBytes={}",
                    normalizedProvider, verified.providerEventId(), verified.eventType(), rawBody.length);
            return new IngressResult(saved, false);
        } catch (DataIntegrityViolationException race) {
            PaymentWebhookEvent saved = repository.findByProviderAndProviderEventId(
                            normalizedProvider, verified.providerEventId())
                    .orElseThrow(() -> race);
            if (!saved.getPayloadSha256().equals(payloadHash)) {
                throw new AppException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
            }
            return new IngressResult(saved, true);
        }
    }

    private static void validate(PaymentWebhookVerifier.VerifiedWebhook webhook) {
        if (webhook == null || isBlank(webhook.providerEventId()) || isBlank(webhook.eventType())) {
            throw new AppException(ErrorCode.INVALID_PAYMENT_WEBHOOK);
        }
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new AppException(ErrorCode.INVALID_PAYMENT_WEBHOOK);
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(byte[] rawBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record IngressResult(PaymentWebhookEvent event, boolean duplicate) {}
}

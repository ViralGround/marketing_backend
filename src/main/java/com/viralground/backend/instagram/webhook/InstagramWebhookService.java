package com.viralground.backend.instagram.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.meta.MetaInstagramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class InstagramWebhookService {
    private static final Logger log = LoggerFactory.getLogger(InstagramWebhookService.class);
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;

    private final MetaInstagramWebhookVerifier verifier;
    private final InstagramWebhookDeliveryRepository repository;
    private final MetaInstagramProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InstagramWebhookService(MetaInstagramWebhookVerifier verifier,
                                   InstagramWebhookDeliveryRepository repository,
                                   MetaInstagramProperties properties,
                                   ObjectMapper objectMapper,
                                   Clock clock) {
        this.verifier = verifier;
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Acceptance accept(byte[] payload, String signature) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new InstagramIntegrationException("INSTAGRAM_WEBHOOK_INVALID",
                    "잘못된 webhook 요청입니다", HttpStatus.BAD_REQUEST);
        }
        if (!verifier.validSignature(payload, signature)) {
            log.warn("event=instagram_webhook_rejected reason=invalid_signature payloadBytes={}", payload.length);
            throw new InstagramIntegrationException("INSTAGRAM_WEBHOOK_SIGNATURE_INVALID",
                    "유효하지 않은 webhook 서명입니다", HttpStatus.UNAUTHORIZED);
        }

        String hash = sha256(payload);
        if (repository.existsByEventHash(hash)) {
            log.info("event=instagram_webhook_duplicate eventHash={}", hash.substring(0, 12));
            return Acceptance.DUPLICATE;
        }
        int entryCount = entryCount(payload);
        try {
            repository.saveAndFlush(InstagramWebhookDelivery.builder()
                    .eventHash(hash)
                    .entryCount(entryCount)
                    .receivedAt(LocalDateTime.now(clock))
                    .build());
        } catch (DataIntegrityViolationException duplicateRace) {
            log.info("event=instagram_webhook_duplicate eventHash={}", hash.substring(0, 12));
            return Acceptance.DUPLICATE;
        }
        log.info("event=instagram_webhook_accepted eventHash={} entries={} payloadBytes={}",
                hash.substring(0, 12), entryCount, payload.length);
        return Acceptance.ACCEPTED;
    }

    public boolean verifyChallenge(String mode, String verifyToken) {
        return "subscribe".equals(mode) && verifier.validVerifyToken(verifyToken);
    }

    @Scheduled(cron = "0 43 3 * * *", zone = "UTC")
    @Transactional
    public void purgeOldDeliveries() {
        long deleted = repository.deleteByReceivedAtBefore(
                LocalDateTime.now(clock).minusDays(properties.webhookRetentionDays()));
        if (deleted > 0) {
            log.info("event=instagram_webhook_dedupe_purged deleted={}", deleted);
        }
    }

    private int entryCount(byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode entries = root.path("entry");
            if (!"instagram".equals(root.path("object").asText())
                    || !entries.isArray() || entries.size() > 100) {
                throw invalidPayload();
            }
            return entries.size();
        } catch (IOException e) {
            throw invalidPayload();
        }
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static InstagramIntegrationException invalidPayload() {
        return new InstagramIntegrationException("INSTAGRAM_WEBHOOK_INVALID",
                "잘못된 webhook 요청입니다", HttpStatus.BAD_REQUEST);
    }

    public enum Acceptance { ACCEPTED, DUPLICATE }
}

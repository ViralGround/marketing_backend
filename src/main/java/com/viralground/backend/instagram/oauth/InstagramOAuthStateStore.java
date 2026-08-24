package com.viralground.backend.instagram.oauth;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.meta.MetaInstagramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;

/** OAuth nonce는 원문을 저장하지 않고 SHA-256만 저장하며, 짧은 TTL과 일회성 소비를 강제한다. */
@Component
public class InstagramOAuthStateStore {

    private static final Logger log = LoggerFactory.getLogger(InstagramOAuthStateStore.class);
    private static final int NONCE_BYTES = 32;

    private final InstagramOAuthStateRepository repository;
    private final MetaInstagramProperties properties;
    private final Clock clock;
    private final SecureRandom random;
    private final PreproductionScheduledMutationGuard scheduledMutationGuard;

    @Value("${app.scheduling.enabled:false}")
    private boolean schedulingEnabled;

    @Value("${instagram.oauth-state.cleanup-enabled:false}")
    private boolean cleanupEnabled;

    @Value("${features.instagram.enabled:false}")
    private boolean instagramFeatureEnabled = false;

    @Autowired
    public InstagramOAuthStateStore(InstagramOAuthStateRepository repository,
                                     MetaInstagramProperties properties,
                                    Clock clock,
                                    PreproductionScheduledMutationGuard scheduledMutationGuard) {
        this(repository, properties, clock, new SecureRandom(), scheduledMutationGuard);
    }

    InstagramOAuthStateStore(InstagramOAuthStateRepository repository,
                              MetaInstagramProperties properties,
                              Clock clock,
                              SecureRandom random,
                              PreproductionScheduledMutationGuard scheduledMutationGuard) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.random = random;
        this.scheduledMutationGuard = scheduledMutationGuard;
    }

    @Transactional
    public IssuedState issue(int creatorId) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.invalidateUnusedByCreatorId(creatorId, now);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        LocalDateTime expiresAt = now.plus(properties.stateTtl());
        repository.save(InstagramOAuthState.builder()
                .stateHash(hash(raw))
                .creatorId(creatorId)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build());
        log.info("event=instagram_oauth_state_issued creatorId={} expiresAt={}", creatorId, expiresAt);
        return new IssuedState(raw, expiresAt);
    }

    /** 외부 Meta 호출 전에 별도 트랜잭션으로 state를 소비해 실패 후 replay도 막는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimedState claim(String rawState) {
        if (rawState == null || rawState.isBlank()) {
            throw invalidState();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        InstagramOAuthState state = repository.findByStateHashForUpdate(hash(rawState))
                .orElseThrow(InstagramOAuthStateStore::invalidState);
        if (state.getUsedAt() != null || !state.getExpiresAt().isAfter(now)) {
            throw invalidState();
        }
        state.setUsedAt(now);
        repository.save(state);
        log.info("event=instagram_oauth_state_claimed creatorId={}", state.getCreatorId());
        return new ClaimedState(state.getCreatorId(), state.getId());
    }

    /** Must be called while the creator connection row is locked. */
    @Transactional(readOnly = true)
    public boolean isLatestAttempt(ClaimedState claimedState) {
        return repository.findTopByCreatorIdOrderByIdDesc(claimedState.creatorId())
                .map(latest -> latest.getId().equals(claimedState.stateId()))
                .orElse(false);
    }

    /** Disconnect invalidates every callback that has not already been claimed. */
    @Transactional
    public void invalidateUnusedForCreator(int creatorId) {
        repository.invalidateUnusedByCreatorId(creatorId, LocalDateTime.now(clock));
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "UTC")
    @Transactional
    public void purgeExpired() {
        if (!schedulingEnabled || !cleanupEnabled || !instagramFeatureEnabled) return;
        scheduledMutationGuard.requireSafe();
        long deleted = repository.deleteByExpiresAtBefore(LocalDateTime.now(clock).minusDays(1));
        if (deleted > 0) {
            log.info("event=instagram_oauth_state_purged deleted={}", deleted);
        }
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static InstagramIntegrationException invalidState() {
        return new InstagramIntegrationException("INSTAGRAM_INVALID_STATE",
                "인스타그램 연결 요청이 만료되었거나 이미 사용되었습니다", HttpStatus.BAD_REQUEST);
    }

    public record IssuedState(String value, LocalDateTime expiresAt) {}
    public record ClaimedState(int creatorId, long stateId) {}
}

package com.viralground.backend.service;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.event.InstagramConnectedEvent;
import com.viralground.backend.instagram.InstagramConnectionProvider;
import com.viralground.backend.instagram.InstagramTokenCipher;
import com.viralground.backend.instagram.oauth.InstagramOAuthStateStore;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditEvent;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/** Serializes OAuth completion with begin/disconnect and persists no stale authorization. */
@Component
public class InstagramAuthorizationPersistenceCoordinator {

    private final CreatorInstagramConnectionRepository repository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final InstagramOAuthStateStore stateStore;
    private final InstagramTokenCipher tokenCipher;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final String providerName;

    public InstagramAuthorizationPersistenceCoordinator(
            CreatorInstagramConnectionRepository repository,
            CreatorProfileRepository creatorProfileRepository,
            InstagramOAuthStateStore stateStore,
            InstagramTokenCipher tokenCipher,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            @Value("${instagram.provider}") String providerName) {
        this.repository = repository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.stateStore = stateStore;
        this.tokenCipher = tokenCipher;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.providerName = providerName.toUpperCase(Locale.ROOT);
    }

    @Transactional
    public ApplyOutcome apply(
            InstagramOAuthStateStore.ClaimedState claimedState,
            InstagramConnectionProvider.AuthorizationResult authorization) {
        CreatorInstagramConnection connection = repository
                .findByCreatorIdForUpdate(claimedState.creatorId())
                .orElse(null);
        if (connection == null || connection.getStatus() != ConnectionStatus.PENDING
                || !stateStore.isLatestAttempt(claimedState)) {
            return ApplyOutcome.SUPERSEDED;
        }

        String profile = creatorProfileRepository.findByMemberId(claimedState.creatorId())
                .map(CreatorProfile::getInstagramId)
                .map(InstagramConnectionService::normalizeHandle)
                .orElse(null);
        String connected = InstagramConnectionService.normalizeHandle(authorization.username());
        if (profile == null || connected == null || !profile.equals(connected)) {
            return ApplyOutcome.ACCOUNT_MISMATCH;
        }
        if (repository.existsByProviderAccountIdAndCreatorIdNot(
                authorization.accountId(), claimedState.creatorId())) {
            return ApplyOutcome.DUPLICATE_ACCOUNT;
        }

        connection.setProvider(providerName);
        connection.setProviderUserId(authorization.accountId());
        connection.setProviderAccountId(authorization.accountId());
        connection.setIgUsername(authorization.username());
        connection.setEncryptedAccessToken(tokenCipher.encrypt(authorization.accessToken()));
        connection.setAccessTokenExpiresAt(
                LocalDateTime.ofInstant(authorization.expiresAt(), ZoneOffset.UTC));
        connection.setTokenRefreshedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setConnectedAt(LocalDateTime.now(clock));
        connection.setLastError(null);
        repository.save(connection);
        eventPublisher.publishEvent(new InstagramConnectedEvent(claimedState.creatorId()));
        eventPublisher.publishEvent(new AuditEvent(claimedState.creatorId(), "CREATOR",
                AuditAction.SOCIAL_ACCOUNT_CONNECTED, "instagramConnection",
                claimedState.creatorId(), "SUCCESS", providerName));
        return ApplyOutcome.APPLIED;
    }

    public enum ApplyOutcome {
        APPLIED,
        SUPERSEDED,
        ACCOUNT_MISMATCH,
        DUPLICATE_ACCOUNT
    }
}

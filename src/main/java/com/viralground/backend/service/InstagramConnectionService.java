package com.viralground.backend.service;

import com.viralground.backend.dto.creator.InstagramAuthorizationResponse;
import com.viralground.backend.dto.creator.InstagramConnectionResponse;
import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.event.InstagramConnectedEvent;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditEvent;
import com.viralground.backend.instagram.InstagramConnectionProvider;
import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.InstagramTokenCipher;
import com.viralground.backend.instagram.oauth.InstagramOAuthStateStore;
import com.viralground.backend.instagram.oauth.InstagramOAuthStateStore.IssuedState;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/** Meta OAuth와 로컬 연결 상태를 조정한다. authorization code와 access token은 로그에 남기지 않는다. */
@Service
public class InstagramConnectionService {

    private static final Logger log = LoggerFactory.getLogger(InstagramConnectionService.class);

    private final CreatorInstagramConnectionRepository repository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final InstagramConnectionProvider provider;
    private final InstagramTokenCipher tokenCipher;
    private final InstagramOAuthStateStore stateStore;
    private final InstagramConnectionFailureRecorder failureRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final String providerName;

    public InstagramConnectionService(
            CreatorInstagramConnectionRepository repository,
            CreatorProfileRepository creatorProfileRepository,
            InstagramConnectionProvider provider,
            InstagramTokenCipher tokenCipher,
            InstagramOAuthStateStore stateStore,
            InstagramConnectionFailureRecorder failureRecorder,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            @Value("${instagram.provider}") String providerName) {
        this.repository = repository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.provider = provider;
        this.tokenCipher = tokenCipher;
        this.stateStore = stateStore;
        this.failureRecorder = failureRecorder;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.providerName = providerName.toUpperCase(Locale.ROOT);
    }

    /** 인증된 크리에이터에게만 짧은 수명의 일회용 state가 포함된 OAuth URL을 발급한다. */
    @Transactional
    public InstagramAuthorizationResponse beginAuthorization(int creatorId) {
        String profileHandle = requiredProfileHandle(creatorId);
        IssuedState state = stateStore.issue(creatorId);
        CreatorInstagramConnection connection = findOrCreate(creatorId);
        connection.setProvider(providerName);
        if (connection.getEncryptedAccessToken() == null) {
            connection.setStatus(ConnectionStatus.PENDING);
        }
        connection.setLastError(null);
        repository.save(connection);
        log.info("event=instagram_oauth_started creatorId={} provider={}", creatorId, providerName);
        return new InstagramAuthorizationResponse(
                provider.buildAuthorizationUrl(state.value(), profileHandle),
                state.expiresAt().atZone(clock.getZone()).toInstant());
    }

    /** public callback에서 state를 먼저 일회성 소비한 뒤 server-side code exchange를 수행한다. */
    @Transactional
    public void completeAuthorization(String state, String code) {
        int creatorId = stateStore.claim(state);
        InstagramConnectionProvider.AuthorizationResult authorization = null;
        try {
            authorization = provider.exchangeAuthorizationCode(code);
            applyVerifiedAuthorization(creatorId, authorization);
        } catch (InstagramIntegrationException e) {
            failureRecorder.record(creatorId, e.getMessage());
            log.warn("event=instagram_oauth_failed creatorId={} code={}", creatorId, e.getCode());
            throw e;
        } catch (RuntimeException e) {
            if (authorization != null) {
                try {
                    provider.revoke(authorization);
                } catch (RuntimeException revokeFailure) {
                    log.error("event=instagram_failed_authorization_revoke_failed creatorId={}",
                            creatorId, revokeFailure);
                }
            }
            failureRecorder.record(creatorId, "인스타그램 연결 중 오류가 발생했습니다. 다시 시도해 주세요.");
            log.error("event=instagram_oauth_failed creatorId={} code=UNEXPECTED", creatorId, e);
            throw new InstagramIntegrationException("INSTAGRAM_CONNECT_FAILED",
                    "인스타그램 연결 중 오류가 발생했습니다. 다시 시도해 주세요.",
                    HttpStatus.SERVICE_UNAVAILABLE, e);
        }
    }

    /** 사용자가 Meta 동의 화면을 취소해도 state를 소비해 replay를 막는다. */
    public void cancelAuthorization(String state) {
        int creatorId = stateStore.claim(state);
        failureRecorder.record(creatorId, "인스타그램 연결이 취소되었습니다.");
        log.info("event=instagram_oauth_cancelled creatorId={}", creatorId);
    }

    @Transactional(readOnly = true)
    public InstagramConnectionResponse getConnection(int creatorId) {
        String handle = profileHandle(creatorId);
        return repository.findByCreatorId(creatorId)
                .map(connection -> InstagramConnectionResponse.from(connection, handle))
                .orElseGet(() -> InstagramConnectionResponse.none(handle));
    }

    /** Meta 권한 철회가 성공한 뒤에만 로컬 토큰과 식별자를 지운다. */
    @Transactional
    public void disconnect(int creatorId) {
        repository.findByCreatorId(creatorId).ifPresent(connection -> {
            provider.revoke(connection);
            connection.setStatus(ConnectionStatus.DISCONNECTED);
            connection.setProviderAccountId(null);
            connection.setProviderUserId(null);
            connection.setIgUsername(null);
            connection.setEncryptedAccessToken(null);
            connection.setAccessTokenExpiresAt(null);
            connection.setTokenRefreshedAt(null);
            connection.setConnectedAt(null);
            connection.setLastError(null);
            repository.save(connection);
            eventPublisher.publishEvent(new AuditEvent(creatorId, "CREATOR",
                    AuditAction.SOCIAL_ACCOUNT_DISCONNECTED, "instagramConnection",
                    creatorId, "SUCCESS", null));
            log.info("event=instagram_disconnected creatorId={} provider={}", creatorId, providerName);
        });
    }

    @Transactional
    protected void applyVerifiedAuthorization(
            int creatorId, InstagramConnectionProvider.AuthorizationResult authorization) {
        String profile = normalizeHandle(requiredProfileHandle(creatorId));
        String connected = normalizeHandle(authorization.username());
        if (connected == null || !profile.equals(connected)) {
            try {
                provider.revoke(authorization);
            } catch (RuntimeException revokeFailure) {
                log.error("event=instagram_mismatch_revoke_failed creatorId={}", creatorId, revokeFailure);
            }
            log.warn("event=instagram_account_mismatch creatorId={}", creatorId);
            throw new InstagramIntegrationException("INSTAGRAM_ACCOUNT_MISMATCH",
                    "프로필에 등록한 Instagram 계정으로 다시 연결해 주세요", HttpStatus.BAD_REQUEST);
        }

        if (repository.existsByProviderAccountIdAndCreatorIdNot(authorization.accountId(), creatorId)) {
            try {
                provider.revoke(authorization);
            } catch (RuntimeException revokeFailure) {
                log.error("event=instagram_duplicate_account_revoke_failed creatorId={}",
                        creatorId, revokeFailure);
            }
            throw new InstagramIntegrationException("INSTAGRAM_ACCOUNT_ALREADY_LINKED",
                    "이미 다른 ViralGround 계정에 연결된 Instagram 계정입니다", HttpStatus.CONFLICT);
        }

        CreatorInstagramConnection connection = findOrCreate(creatorId);
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
        eventPublisher.publishEvent(new InstagramConnectedEvent(creatorId));
        eventPublisher.publishEvent(new AuditEvent(creatorId, "CREATOR",
                AuditAction.SOCIAL_ACCOUNT_CONNECTED, "instagramConnection",
                creatorId, "SUCCESS", providerName));
        log.info("event=instagram_connected creatorId={} provider={} tokenExpiresAt={}",
                creatorId, providerName, authorization.expiresAt());
    }

    private CreatorInstagramConnection findOrCreate(int creatorId) {
        return repository.findByCreatorId(creatorId)
                .orElseGet(() -> CreatorInstagramConnection.builder()
                        .creatorId(creatorId)
                        .provider(providerName)
                        .build());
    }

    private String requiredProfileHandle(int creatorId) {
        String handle = profileHandle(creatorId);
        if (normalizeHandle(handle) == null) {
            throw new InstagramIntegrationException("INSTAGRAM_PROFILE_HANDLE_REQUIRED",
                    "프로필에 Instagram 아이디를 먼저 등록해 주세요", HttpStatus.BAD_REQUEST);
        }
        return handle;
    }

    private String profileHandle(int creatorId) {
        return creatorProfileRepository.findByMemberId(creatorId)
                .map(CreatorProfile::getInstagramId)
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
    }

    static String normalizeHandle(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
}

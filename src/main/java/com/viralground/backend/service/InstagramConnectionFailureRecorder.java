package com.viralground.backend.service;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.instagram.oauth.InstagramOAuthStateStore;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/** OAuth 외부 호출 실패 상태를 성공 트랜잭션과 분리해 확실히 기록한다. */
@Component
public class InstagramConnectionFailureRecorder {

    private final CreatorInstagramConnectionRepository repository;
    private final InstagramOAuthStateStore stateStore;
    private final String providerName;

    public InstagramConnectionFailureRecorder(
            CreatorInstagramConnectionRepository repository,
            InstagramOAuthStateStore stateStore,
            @Value("${instagram.provider}") String providerName) {
        this.repository = repository;
        this.stateStore = stateStore;
        this.providerName = providerName.toUpperCase(Locale.ROOT);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            InstagramOAuthStateStore.ClaimedState claimedState,
            String safeMessage) {
        CreatorInstagramConnection connection = repository
                .findByCreatorIdForUpdate(claimedState.creatorId())
                .orElse(null);
        if (connection == null || connection.getStatus() != ConnectionStatus.PENDING
                || !stateStore.isLatestAttempt(claimedState)) {
            return;
        }
        connection.setProvider(providerName);
        connection.setStatus(connection.getEncryptedAccessToken() == null
                ? ConnectionStatus.ERROR : ConnectionStatus.CONNECTED);
        connection.setLastError(safeMessage);
        repository.save(connection);
    }
}

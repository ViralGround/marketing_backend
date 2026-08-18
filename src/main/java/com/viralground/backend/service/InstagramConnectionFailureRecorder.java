package com.viralground.backend.service;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
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
    private final String providerName;

    public InstagramConnectionFailureRecorder(
            CreatorInstagramConnectionRepository repository,
            @Value("${instagram.provider}") String providerName) {
        this.repository = repository;
        this.providerName = providerName.toUpperCase(Locale.ROOT);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(int creatorId, String safeMessage) {
        CreatorInstagramConnection connection = repository.findByCreatorId(creatorId)
                .orElseGet(() -> CreatorInstagramConnection.builder()
                        .creatorId(creatorId).provider(providerName).build());
        connection.setProvider(providerName);
        connection.setStatus(connection.getEncryptedAccessToken() == null
                ? ConnectionStatus.ERROR : ConnectionStatus.CONNECTED);
        connection.setLastError(safeMessage);
        repository.save(connection);
    }
}

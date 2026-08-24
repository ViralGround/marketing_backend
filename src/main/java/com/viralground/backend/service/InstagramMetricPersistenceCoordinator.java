package com.viralground.backend.service;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.entity.MetricSource;
import com.viralground.backend.entity.ReelMetricSnapshot;
import com.viralground.backend.instagram.ReelMetrics;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.ReelMetricSnapshotRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Persists a metric only when the token-bearing connection CAS still wins over revoke. */
@Component
public class InstagramMetricPersistenceCoordinator {

    private final CreatorInstagramConnectionRepository connectionRepository;
    private final ReelMetricSnapshotRepository snapshotRepository;

    public InstagramMetricPersistenceCoordinator(
            CreatorInstagramConnectionRepository connectionRepository,
            ReelMetricSnapshotRepository snapshotRepository) {
        this.connectionRepository = connectionRepository;
        this.snapshotRepository = snapshotRepository;
    }

    @Transactional
    public boolean recordSuccess(
            CreatorInstagramConnection connection,
            String expectedEncryptedAccessToken,
            int applicationId,
            ReelMetrics metrics,
            LocalDateTime capturedAt,
            LocalDateTime updatedAt) {
        int updated = connectionRepository.markSyncSucceededIfConnected(
                connection.getId(), ConnectionStatus.CONNECTED,
                expectedEncryptedAccessToken, connection.getEncryptedAccessToken(),
                connection.getAccessTokenExpiresAt(), connection.getTokenRefreshedAt(),
                capturedAt, updatedAt);
        if (updated == 0) {
            return false;
        }
        snapshotRepository.save(ReelMetricSnapshot.builder()
                .applicationId(applicationId)
                .views(metrics.views()).likes(metrics.likes())
                .comments(metrics.comments()).shares(metrics.shares())
                .source(MetricSource.AUTO)
                .capturedAt(capturedAt)
                .build());
        return true;
    }

    @Transactional
    public boolean recordFailure(
            CreatorInstagramConnection connection,
            String expectedEncryptedAccessToken,
            ConnectionStatus newStatus,
            String safeError,
            LocalDateTime updatedAt) {
        return connectionRepository.markSyncFailedIfConnected(
                connection.getId(), ConnectionStatus.CONNECTED, newStatus,
                expectedEncryptedAccessToken, connection.getEncryptedAccessToken(),
                connection.getAccessTokenExpiresAt(), connection.getTokenRefreshedAt(),
                safeError, updatedAt) == 1;
    }
}

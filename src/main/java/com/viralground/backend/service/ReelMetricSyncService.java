package com.viralground.backend.service;

import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.entity.MetricSource;
import com.viralground.backend.entity.ReelMetricSnapshot;
import com.viralground.backend.instagram.InstagramConnectionProvider;
import com.viralground.backend.instagram.ReelMetrics;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.ReelMetricSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 연동(CONNECTED)된 크리에이터의 릴스 지표를 동기화해 {@link ReelMetricSnapshot}(source=AUTO)으로 적재한다.
 * 스케줄 배치(기본 매일 00:00 KST) + 관리자 수동 트리거({@code POST /admin/reel-analytics/sync})로 호출된다.
 *
 * <p>한 릴스의 fetch 가 실패해도 연결의 {@code lastError} 만 기록하고 나머지는 계속 동기화한다.
 */
@Service
@RequiredArgsConstructor
public class ReelMetricSyncService {

    private static final Logger log = LoggerFactory.getLogger(ReelMetricSyncService.class);

    private final CreatorInstagramConnectionRepository connectionRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final ReelMetricSnapshotRepository snapshotRepository;
    private final InstagramConnectionProvider connectionProvider;
    private final Clock clock;

    @Value("${instagram.sync.max-items-per-run:200}")
    private int maxItemsPerRun = 200;

    /** 스케줄 자동 동기화. cron 은 {@code instagram.sync.cron}(기본 매일 00:00 KST). */
    @Scheduled(cron = "${instagram.sync.cron:0 0 0 * * *}", zone = "Asia/Seoul")
    public void scheduledSync() {
        long startedAt = System.nanoTime();
        log.info("event=instagram_metric_batch_started maxItems={}", boundedMaxItems());
        SyncResult result = syncAll();
        log.info("event=instagram_metric_batch_completed synced={} failed={} durationMs={}",
                result.synced(), result.failed(), (System.nanoTime() - startedAt) / 1_000_000);
    }

    /**
     * 연결된 크리에이터의 submissionUrl 보유 릴스를 순회해 지표 스냅샷을 적재한다.
     * @return 성공/실패 건수 요약
     */
    public SyncResult syncAll() {
        List<CreatorInstagramConnection> connections =
                connectionRepository.findByStatus(ConnectionStatus.CONNECTED);
        if (connections.isEmpty()) {
            return new SyncResult(0, 0);
        }

        Map<Integer, CreatorInstagramConnection> connByCreator = new HashMap<>();
        for (CreatorInstagramConnection c : connections) {
            connByCreator.put(c.getCreatorId(), c);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int synced = 0;
        int failed = 0;

        for (CampaignApplication app : applicationRepository.findBySubmissionUrlIsNotNull(
                PageRequest.of(0, boundedMaxItems(), Sort.by("id").ascending()))) {
            String url = app.getSubmissionUrl();
            if (url == null || url.isBlank()) {
                continue;
            }
            CreatorInstagramConnection conn = connByCreator.get(app.getCreatorId());
            if (conn == null) {
                continue; // 미연결 크리에이터의 릴스는 스킵(수동 폴백은 대시보드가 처리)
            }
            if (syncOne(app, conn, now)) {
                synced++;
            } else {
                failed++;
            }
        }
        return new SyncResult(synced, failed);
    }

    /**
     * 한 크리에이터의 릴스만 동기화한다(연결 직후 초기 동기화용). 미연결이면 no-op.
     *
     * @param creatorId 대상 크리에이터 id
     * @return 성공/실패 건수 요약
     */
    public SyncResult syncCreator(int creatorId) {
        CreatorInstagramConnection conn = connectionRepository.findByCreatorId(creatorId)
                .filter(c -> c.getStatus() == ConnectionStatus.CONNECTED)
                .orElse(null);
        if (conn == null) {
            return new SyncResult(0, 0);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int synced = 0;
        int failed = 0;

        for (CampaignApplication app : applicationRepository.findByCreatorIdAndSubmissionUrlIsNotNull(
                creatorId, PageRequest.of(0, boundedMaxItems(), Sort.by("id").ascending()))) {
            String url = app.getSubmissionUrl();
            if (url == null || url.isBlank()) {
                continue;
            }
            if (syncOne(app, conn, now)) {
                synced++;
            } else {
                failed++;
            }
        }
        return new SyncResult(synced, failed);
    }

    /** 릴스 1건 동기화: fetch → 스냅샷 저장(AUTO). 성공 true / 실패 false(예외 격리, lastError 기록). */
    private boolean syncOne(CampaignApplication app, CreatorInstagramConnection conn, LocalDateTime now) {
        try {
            ReelMetrics m = connectionProvider.fetchReelMetrics(conn, app.getSubmissionUrl());
            snapshotRepository.save(ReelMetricSnapshot.builder()
                    .applicationId(app.getId())
                    .views(m.views()).likes(m.likes()).comments(m.comments()).shares(m.shares())
                    .source(MetricSource.AUTO)
                    .capturedAt(now)
                    .build());
            conn.setLastSyncedAt(now);
            conn.setLastError(null);
            connectionRepository.save(conn);
            return true;
        } catch (Exception e) {
            String safeError = e instanceof com.viralground.backend.instagram.InstagramIntegrationException
                    ? e.getMessage() : "Instagram 지표 동기화 중 일시적인 오류가 발생했습니다.";
            if (e instanceof com.viralground.backend.instagram.InstagramIntegrationException integrationError
                    && ("INSTAGRAM_RECONNECT_REQUIRED".equals(integrationError.getCode())
                    || "INSTAGRAM_TOKEN_EXPIRED".equals(integrationError.getCode()))) {
                conn.setStatus(ConnectionStatus.ERROR);
            }
            conn.setLastError(safeError);
            connectionRepository.save(conn);
            log.warn("event=instagram_metric_sync_failed applicationId={} creatorId={} errorType={}",
                    app.getId(), app.getCreatorId(), e.getClass().getSimpleName());
            return false;
        }
    }

    private int boundedMaxItems() {
        return Math.max(1, Math.min(maxItemsPerRun, 1000));
    }

    /** 동기화 결과 요약. */
    public record SyncResult(int synced, int failed) {}
}

package com.viralground.backend.service;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
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
import com.viralground.backend.service.ReelMetricSyncService.SyncResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReelMetricSyncServiceTest {

    private final CreatorInstagramConnectionRepository connectionRepository =
            mock(CreatorInstagramConnectionRepository.class);
    private final CampaignApplicationRepository applicationRepository =
            mock(CampaignApplicationRepository.class);
    private final ReelMetricSnapshotRepository snapshotRepository =
            mock(ReelMetricSnapshotRepository.class);
    private final InstagramConnectionProvider connectionProvider =
            mock(InstagramConnectionProvider.class);
    private final InstagramMetricPersistenceCoordinator persistenceCoordinator =
            new InstagramMetricPersistenceCoordinator(connectionRepository, snapshotRepository);

    private final LocalDateTime fixedNow = LocalDateTime.of(2026, 6, 10, 0, 0);
    private final Clock clock = Clock.fixed(
            fixedNow.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    private final ReelMetricSyncService service = new ReelMetricSyncService(
            connectionRepository, applicationRepository, connectionProvider,
            persistenceCoordinator, clock, mock(PreproductionScheduledMutationGuard.class));

    {
        ReflectionTestUtils.setField(service, "maxItemsPerRun", 200);
        ReflectionTestUtils.setField(service, "instagramFeatureEnabled", true);
    }

    @BeforeEach
    void connectedConditionalUpdatesSucceedByDefault() {
        when(connectionRepository.markSyncSucceededIfConnected(
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(connectionRepository.markSyncFailedIfConnected(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
    }

    private CreatorInstagramConnection connection(int creatorId) {
        return CreatorInstagramConnection.builder()
                .id(creatorId).creatorId(creatorId).status(ConnectionStatus.CONNECTED).build();
    }

    private CampaignApplication app(int id, int creatorId, String url) {
        return CampaignApplication.builder().id(id).creatorId(creatorId).submissionUrl(url).build();
    }

    @Test
    void 연결된_크리에이터의_릴스만_동기화해_AUTO_스냅샷을_저장한다() {
        // given — creator 1 연결됨, creator 2 미연결
        when(connectionRepository.findByStatus(ConnectionStatus.CONNECTED))
                .thenReturn(List.of(connection(1)));
        CampaignApplication connected = app(100, 1, "https://insta/reel/aaa/");
        CampaignApplication notConnected = app(101, 2, "https://insta/reel/bbb/");
        when(applicationRepository.findBySubmissionUrlIsNotNull(any(Pageable.class)))
                .thenReturn(List.of(connected, notConnected));
        when(connectionProvider.fetchReelMetrics(any(), eq("https://insta/reel/aaa/")))
                .thenReturn(new ReelMetrics(1000, 100, 10, 5, List.of()));

        // when
        SyncResult result = service.syncAll();

        // then — 연결된 릴스 1건만 스냅샷 저장(AUTO), 미연결은 fetch 도 안 함
        verify(connectionProvider, never()).fetchReelMetrics(any(), eq("https://insta/reel/bbb/"));
        ArgumentCaptor<ReelMetricSnapshot> captor = ArgumentCaptor.forClass(ReelMetricSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        ReelMetricSnapshot saved = captor.getValue();
        assertThat(saved.getApplicationId()).isEqualTo(100);
        assertThat(saved.getViews()).isEqualTo(1000);
        assertThat(saved.getShares()).isEqualTo(5);
        assertThat(saved.getSource()).isEqualTo(MetricSource.AUTO);
        assertThat(saved.getCapturedAt()).isEqualTo(fixedNow);

        assertThat(result.synced()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    void 동기화_성공시_연결의_lastSyncedAt_을_갱신한다() {
        // given
        CreatorInstagramConnection conn = connection(1);
        when(connectionRepository.findByStatus(ConnectionStatus.CONNECTED)).thenReturn(List.of(conn));
        when(applicationRepository.findBySubmissionUrlIsNotNull(any(Pageable.class)))
                .thenReturn(List.of(app(100, 1, "https://insta/reel/aaa/")));
        when(connectionProvider.fetchReelMetrics(any(), any()))
                .thenReturn(new ReelMetrics(1000, 100, 10, 5, List.of()));

        // when
        service.syncAll();

        // then
        assertThat(conn.getLastSyncedAt()).isEqualTo(fixedNow);
        verify(connectionRepository).markSyncSucceededIfConnected(
                eq(conn.getId()), eq(ConnectionStatus.CONNECTED), any(), any(), any(), any(),
                eq(fixedNow), any());
    }

    @Test
    void fetch_예외시_lastError_를_기록하고_다른_건은_계속한다() {
        // given — creator 1(실패), creator 2(성공)
        CreatorInstagramConnection conn1 = connection(1);
        CreatorInstagramConnection conn2 = connection(2);
        when(connectionRepository.findByStatus(ConnectionStatus.CONNECTED))
                .thenReturn(List.of(conn1, conn2));
        when(applicationRepository.findBySubmissionUrlIsNotNull(any(Pageable.class))).thenReturn(List.of(
                app(100, 1, "https://insta/reel/fail/"),
                app(200, 2, "https://insta/reel/ok/")));
        when(connectionProvider.fetchReelMetrics(any(), eq("https://insta/reel/fail/")))
                .thenThrow(new RuntimeException("upstream 5xx with secret details"));
        when(connectionProvider.fetchReelMetrics(any(), eq("https://insta/reel/ok/")))
                .thenReturn(new ReelMetrics(2000, 200, 20, 10, List.of()));

        // when
        SyncResult result = service.syncAll();

        // then — 실패 1건 lastError 기록, 성공 1건 스냅샷 저장
        assertThat(conn1.getLastError()).isEqualTo("Instagram 지표 동기화 중 일시적인 오류가 발생했습니다.");
        assertThat(conn1.getLastError()).doesNotContain("secret");
        verify(snapshotRepository).save(any(ReelMetricSnapshot.class)); // 성공 건만
        assertThat(result.synced()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void 공백_submissionUrl_은_스킵한다() {
        // given — 연결됐지만 submissionUrl 이 공백
        when(connectionRepository.findByStatus(ConnectionStatus.CONNECTED))
                .thenReturn(List.of(connection(1)));
        when(applicationRepository.findBySubmissionUrlIsNotNull(any(Pageable.class)))
                .thenReturn(List.of(app(100, 1, "   ")));

        // when
        SyncResult result = service.syncAll();

        // then
        verify(connectionProvider, never()).fetchReelMetrics(any(), any());
        verify(snapshotRepository, never()).save(any());
        assertThat(result.synced()).isZero();
    }

    @Test
    void revokedTokenMarksConnectionErrorAndRequestsReconnect() {
        CreatorInstagramConnection conn = connection(1);
        when(connectionRepository.findByStatus(ConnectionStatus.CONNECTED)).thenReturn(List.of(conn));
        when(applicationRepository.findBySubmissionUrlIsNotNull(any(Pageable.class)))
                .thenReturn(List.of(app(100, 1, "https://insta/reel/revoked/")));
        when(connectionProvider.fetchReelMetrics(any(), any())).thenThrow(
                new com.viralground.backend.instagram.InstagramIntegrationException(
                        "INSTAGRAM_RECONNECT_REQUIRED", "인스타그램을 다시 연결해 주세요",
                        org.springframework.http.HttpStatus.CONFLICT));

        SyncResult result = service.syncAll();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(conn.getStatus()).isEqualTo(ConnectionStatus.ERROR);
        assertThat(conn.getLastError()).isEqualTo("인스타그램을 다시 연결해 주세요");
    }

    @Test
    void syncCreator_는_해당_크리에이터의_릴스만_동기화한다() {
        // given — creator 1 연결됨, creator 1·2 릴스 존재
        when(connectionRepository.findByCreatorId(1)).thenReturn(Optional.of(connection(1)));
        when(applicationRepository.findByCreatorIdAndSubmissionUrlIsNotNull(eq(1), any(Pageable.class)))
                .thenReturn(List.of(app(100, 1, "https://insta/reel/mine/")));
        when(connectionProvider.fetchReelMetrics(any(), eq("https://insta/reel/mine/")))
                .thenReturn(new ReelMetrics(500, 50, 5, 2, List.of()));

        // when
        SyncResult result = service.syncCreator(1);

        // then — 내 릴스만 동기화, 남의 릴스는 fetch 안 함
        verify(snapshotRepository).save(any(ReelMetricSnapshot.class));
        assertThat(result.synced()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    void syncCreator_는_미연결이면_no_op() {
        // given — 연결 없음
        when(connectionRepository.findByCreatorId(9)).thenReturn(Optional.empty());

        // when
        SyncResult result = service.syncCreator(9);

        // then
        verify(connectionProvider, never()).fetchReelMetrics(any(), any());
        assertThat(result.synced()).isZero();
    }

    @Test
    void concurrentDisconnectPreventsStaleMergeAndStopsLaterTokenUse() {
        CreatorInstagramConnection conn = connection(1);
        conn.setEncryptedAccessToken("stale-encrypted-token");
        when(connectionRepository.findByStatus(ConnectionStatus.CONNECTED))
                .thenReturn(List.of(conn));
        when(applicationRepository.findBySubmissionUrlIsNotNull(any(Pageable.class)))
                .thenReturn(List.of(
                        app(100, 1, "https://insta/reel/first/"),
                        app(101, 1, "https://insta/reel/second/")));
        when(connectionProvider.fetchReelMetrics(any(), eq("https://insta/reel/first/")))
                .thenReturn(new ReelMetrics(10, 1, 0, 0, List.of()));
        when(connectionRepository.markSyncSucceededIfConnected(
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        service.syncAll();

        verify(connectionRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
        verify(connectionProvider, never()).fetchReelMetrics(
                any(), eq("https://insta/reel/second/"));
        assertThat(conn.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }
}

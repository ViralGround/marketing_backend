package com.viralground.backend.service;

import com.viralground.backend.dto.admin.ReelAnalyticsResponse;
import com.viralground.backend.dto.admin.ReelAnalyticsResponse.CampaignGroup;
import com.viralground.backend.dto.admin.ReelAnalyticsResponse.ReelItem;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MetricSource;
import com.viralground.backend.entity.ReelMetricSnapshot;
import com.viralground.backend.entity.SubmissionMetric;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.ReelMetricSnapshotRepository;
import com.viralground.backend.repository.SubmissionMetricRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReelAnalyticsServiceTest {

    private final CampaignApplicationRepository applicationRepository = mock(CampaignApplicationRepository.class);
    private final CampaignRepository campaignRepository = mock(CampaignRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final ReelMetricSnapshotRepository snapshotRepository = mock(ReelMetricSnapshotRepository.class);
    private final SubmissionMetricRepository submissionMetricRepository = mock(SubmissionMetricRepository.class);
    private final CreatorInstagramConnectionRepository connectionRepository =
            mock(CreatorInstagramConnectionRepository.class);

    private final ReelAnalyticsService service = new ReelAnalyticsService(
            applicationRepository, campaignRepository, memberRepository,
            snapshotRepository, submissionMetricRepository, connectionRepository);

    private ReelMetricSnapshot snapshot(int appId, long views, long likes, long comments, long shares,
                                        MetricSource source, LocalDateTime at) {
        return ReelMetricSnapshot.builder()
                .applicationId(appId).views(views).likes(likes).comments(comments).shares(shares)
                .source(source).capturedAt(at).build();
    }

    @Test
    void 최신_스냅샷을_캠페인별로_집계하고_총합과_랭킹을_계산한다() {
        // given
        Campaign c1 = Campaign.builder().id(1).title("캠A").brandName("브A").status(CampaignStatus.OPEN).build();
        Campaign c2 = Campaign.builder().id(2).title("캠B").brandName("브B").status(CampaignStatus.CLOSED).build();

        CampaignApplication a1 = CampaignApplication.builder()
                .id(10).campaignId(1).creatorId(100).status(ApplicationStatus.SETTLED)
                .submissionUrl("https://www.instagram.com/reel/aaa/").build();
        CampaignApplication a2 = CampaignApplication.builder()
                .id(11).campaignId(1).creatorId(101).status(ApplicationStatus.SUBMITTED)
                .submissionUrl("https://www.instagram.com/reel/bbb/").build();
        CampaignApplication a3 = CampaignApplication.builder()
                .id(12).campaignId(2).creatorId(100).status(ApplicationStatus.SETTLED)
                .submissionUrl("https://www.instagram.com/reel/ccc/").build();

        when(applicationRepository.findBySubmissionUrlIsNotNull()).thenReturn(List.of(a1, a2, a3));
        when(campaignRepository.findAllById(any())).thenReturn(List.of(c1, c2));
        when(memberRepository.findAllById(any())).thenReturn(List.of(
                Member.builder().id(100).name("크리A").build(),
                Member.builder().id(101).name("크리B").build()));

        LocalDateTime now = LocalDateTime.of(2026, 6, 10, 0, 0);
        when(snapshotRepository.findTopByApplicationIdOrderByCapturedAtDesc(10))
                .thenReturn(Optional.of(snapshot(10, 1000, 100, 10, 5, MetricSource.AUTO, now)));
        when(snapshotRepository.findTopByApplicationIdOrderByCapturedAtDesc(11))
                .thenReturn(Optional.of(snapshot(11, 2000, 200, 20, 10, MetricSource.AUTO, now)));
        when(snapshotRepository.findTopByApplicationIdOrderByCapturedAtDesc(12))
                .thenReturn(Optional.of(snapshot(12, 3000, 300, 30, 15, MetricSource.AUTO, now)));
        when(connectionRepository.countByStatus(ConnectionStatus.CONNECTED)).thenReturn(2L);

        // when
        ReelAnalyticsResponse res = service.getDashboard();

        // then — 요약 총합
        assertThat(res.summary().totalReels()).isEqualTo(3);
        assertThat(res.summary().totalViews()).isEqualTo(6000);
        assertThat(res.summary().totalLikes()).isEqualTo(600);
        assertThat(res.summary().totalComments()).isEqualTo(60);
        assertThat(res.summary().totalShares()).isEqualTo(30); // 5+10+15
        assertThat(res.summary().activeCampaigns()).isEqualTo(1); // OPEN 은 c1 뿐
        assertThat(res.summary().avgEngagementRate()).isCloseTo(0.11, within(0.001));

        // 연동 크리에이터 수
        assertThat(res.connectedCreators()).isEqualTo(2);

        // 캠페인별 묶음
        assertThat(res.campaigns()).hasSize(2);
        CampaignGroup g1 = res.campaigns().stream()
                .filter(g -> g.campaignId() == 1).findFirst().orElseThrow();
        assertThat(g1.reelCount()).isEqualTo(2);
        assertThat(g1.views()).isEqualTo(3000); // aaa(1000)+bbb(2000)
        assertThat(g1.shares()).isEqualTo(15);  // aaa(5)+bbb(10)

        // 랭킹 + source
        assertThat(res.topReels().get(0).views()).isEqualTo(3000);   // ccc 최상위
        assertThat(res.topReels().get(0).shares()).isEqualTo(15);    // ccc 공유수
        assertThat(res.topReels().get(0).creatorId()).isEqualTo(100); // 상세 링크용 creatorId 포함
        assertThat(res.topReels().get(0).source()).isEqualTo("AUTO");
        assertThat(res.topCreators().get(0).creatorName()).isEqualTo("크리A");
        assertThat(res.topCreators().get(0).views()).isEqualTo(4000); // aaa+ccc
    }

    @Test
    void 스냅샷이_없으면_수동_입력_SubmissionMetric_으로_폴백하고_MANUAL_로_표시한다() {
        // given — 스냅샷 없음, SubmissionMetric 있음
        Campaign c = Campaign.builder().id(1).title("캠").brandName("브").status(CampaignStatus.OPEN).build();
        CampaignApplication a = CampaignApplication.builder()
                .id(10).campaignId(1).creatorId(100).submissionUrl("https://insta/reel/x/").build();
        when(applicationRepository.findBySubmissionUrlIsNotNull()).thenReturn(List.of(a));
        when(campaignRepository.findAllById(any())).thenReturn(List.of(c));
        when(memberRepository.findAllById(any())).thenReturn(List.of(Member.builder().id(100).name("크리").build()));
        when(snapshotRepository.findTopByApplicationIdOrderByCapturedAtDesc(10)).thenReturn(Optional.empty());
        when(submissionMetricRepository.findByApplicationId(10)).thenReturn(Optional.of(
                SubmissionMetric.builder().applicationId(10).views(500L).likes(50L).comments(5L).build()));

        // when
        ReelAnalyticsResponse res = service.getDashboard();

        // then — 수동 값 + MANUAL, shares 는 수동 입력에 없으므로 0
        ReelItem item = res.topReels().get(0);
        assertThat(item.views()).isEqualTo(500);
        assertThat(item.likes()).isEqualTo(50);
        assertThat(item.shares()).isZero();
        assertThat(item.source()).isEqualTo("MANUAL");
    }

    @Test
    void 스냅샷도_수동도_없으면_미연동_으로_0_을_표시한다() {
        // given
        Campaign c = Campaign.builder().id(1).title("캠").brandName("브").status(CampaignStatus.OPEN).build();
        CampaignApplication a = CampaignApplication.builder()
                .id(10).campaignId(1).creatorId(100).submissionUrl("https://insta/reel/x/").build();
        when(applicationRepository.findBySubmissionUrlIsNotNull()).thenReturn(List.of(a));
        when(campaignRepository.findAllById(any())).thenReturn(List.of(c));
        when(memberRepository.findAllById(any())).thenReturn(List.of(Member.builder().id(100).name("크리").build()));
        when(snapshotRepository.findTopByApplicationIdOrderByCapturedAtDesc(10)).thenReturn(Optional.empty());
        when(submissionMetricRepository.findByApplicationId(10)).thenReturn(Optional.empty());

        // when
        ReelAnalyticsResponse res = service.getDashboard();

        // then — NONE, 0
        ReelItem item = res.topReels().get(0);
        assertThat(item.views()).isZero();
        assertThat(item.source()).isEqualTo("NONE");
    }

    @Test
    void 추이는_스냅샷_조회수_증가분으로_구성한다() {
        // given — application 10 의 스냅샷 시계열(오래된→최신) 조회수 1000→1500→1800
        Campaign c = Campaign.builder().id(1).title("캠").brandName("브").status(CampaignStatus.OPEN).build();
        CampaignApplication a = CampaignApplication.builder()
                .id(10).campaignId(1).creatorId(100).submissionUrl("https://insta/reel/x/").build();
        when(applicationRepository.findBySubmissionUrlIsNotNull()).thenReturn(List.of(a));
        when(campaignRepository.findAllById(any())).thenReturn(List.of(c));
        when(memberRepository.findAllById(any())).thenReturn(List.of(Member.builder().id(100).name("크리").build()));
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 0, 0);
        when(snapshotRepository.findTopByApplicationIdOrderByCapturedAtDesc(10))
                .thenReturn(Optional.of(snapshot(10, 1800, 180, 18, 9, MetricSource.AUTO, base.plusDays(2))));
        when(snapshotRepository.findByApplicationIdOrderByCapturedAtAsc(10)).thenReturn(List.of(
                snapshot(10, 1000, 100, 10, 5, MetricSource.AUTO, base),
                snapshot(10, 1500, 150, 15, 7, MetricSource.AUTO, base.plusDays(1)),
                snapshot(10, 1800, 180, 18, 9, MetricSource.AUTO, base.plusDays(2))));

        // when
        ReelAnalyticsResponse res = service.getDashboard();

        // then — 연속 증가분 [500, 300] 이 가장 최근 슬롯에 우측 정렬
        assertThat(res.viewsTrend()).hasSize(14);
        assertThat(res.viewsTrend().get(13)).isEqualTo(300); // 1800-1500
        assertThat(res.viewsTrend().get(12)).isEqualTo(500); // 1500-1000
        assertThat(res.viewsTrend().get(11)).isZero();
    }

    @Test
    void 릴스가_없으면_0_으로_채워진_빈_대시보드를_반환한다() {
        when(applicationRepository.findBySubmissionUrlIsNotNull()).thenReturn(List.of());
        when(connectionRepository.countByStatus(ConnectionStatus.CONNECTED)).thenReturn(0L);

        ReelAnalyticsResponse res = service.getDashboard();

        assertThat(res.summary().totalReels()).isZero();
        assertThat(res.summary().totalViews()).isZero();
        assertThat(res.campaigns()).isEmpty();
        assertThat(res.topReels()).isEmpty();
        assertThat(res.topCreators()).isEmpty();
        assertThat(res.viewsTrend()).hasSize(14);
        assertThat(res.viewsTrend()).allMatch(v -> v == 0L);
        assertThat(res.connectedCreators()).isZero();
    }

    @Test
    void 데모_모드면_DB_없이_합성_데이터를_집계한다() {
        // given — analytics.demo-mode=true 를 @Value 필드에 주입
        ReflectionTestUtils.setField(service, "demoMode", true);

        // when
        ReelAnalyticsResponse res = service.getDashboard();

        // then — 합성 데이터(릴스 14건 · 캠페인 5개 · OPEN 4개)
        assertThat(res.summary().totalReels()).isEqualTo(14);
        assertThat(res.summary().activeCampaigns()).isEqualTo(4);
        assertThat(res.summary().totalViews()).isPositive();
        assertThat(res.summary().totalShares()).isPositive();
        assertThat(res.campaigns()).hasSize(5);
        assertThat(res.topReels()).hasSize(5);
        assertThat(res.topCreators()).hasSize(5);
        assertThat(res.viewsTrend()).hasSize(14);
        assertThat(res.viewsTrend()).anyMatch(v -> v > 0);

        // 데모 모드는 DB·지표 소스를 전혀 건드리지 않는다
        verifyNoInteractions(applicationRepository, campaignRepository, memberRepository,
                snapshotRepository, submissionMetricRepository, connectionRepository);
    }
}

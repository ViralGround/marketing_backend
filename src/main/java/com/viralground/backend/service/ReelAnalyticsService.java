package com.viralground.backend.service;

import com.viralground.backend.dto.admin.ReelAnalyticsResponse;
import com.viralground.backend.dto.admin.ReelAnalyticsResponse.CampaignGroup;
import com.viralground.backend.dto.admin.ReelAnalyticsResponse.CreatorRank;
import com.viralground.backend.dto.admin.ReelAnalyticsResponse.ReelItem;
import com.viralground.backend.dto.admin.ReelAnalyticsResponse.Summary;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MetricSource;
import com.viralground.backend.entity.ReelMetricSnapshot;
import com.viralground.backend.entity.SubmissionMetric;
import com.viralground.backend.instagram.ReelMetrics;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.ReelMetricSnapshotRepository;
import com.viralground.backend.repository.SubmissionMetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 관리자 릴스 분석 대시보드 집계. 업로드된(submissionUrl 보유) 릴스 전부를 캠페인별·크리에이터별로 집계한다.
 * 릴스별 지표 출처는 (1) 최신 {@link ReelMetricSnapshot}(자동 동기화) → (2) 수동 {@link SubmissionMetric} 폴백
 * → (3) 미연동(0) 순으로 결정하며, 각 {@code ReelItem.source} 로 노출한다. 추이는 스냅샷 조회수 증가분으로 구성.
 *
 * <p>{@code analytics.demo-mode=true} 면 DB 대신 합성 데이터를 집계한다(로컬 검토·데모 용도).
 * 운영에서는 반드시 false — 기본값 false.
 */
@Service
@RequiredArgsConstructor
public class ReelAnalyticsService {

    private static final int TREND_DAYS = 14;
    private static final int TOP_N = 5;
    /** 최근 14일 일별 조회수의 상대적 형태(상승→정점→완만한 하락). 데모 추이선을 자연스럽게 만든다. */
    private static final double[] TREND_SHAPE =
            {0.6, 0.7, 0.9, 1.1, 1.4, 1.8, 2.4, 3.0, 2.6, 2.2, 1.9, 1.6, 1.4, 1.2};

    private final CampaignApplicationRepository applicationRepository;
    private final CampaignRepository campaignRepository;
    private final MemberRepository memberRepository;
    private final ReelMetricSnapshotRepository snapshotRepository;
    private final SubmissionMetricRepository submissionMetricRepository;
    private final CreatorInstagramConnectionRepository connectionRepository;

    @Value("${analytics.demo-mode:false}")
    private boolean demoMode;

    @Transactional(readOnly = true)
    public ReelAnalyticsResponse getDashboard() {
        if (demoMode) {
            return aggregate(demoRows(), 0);
        }
        int connectedCreators = (int) connectionRepository.countByStatus(ConnectionStatus.CONNECTED);
        return aggregate(realRows(), connectedCreators);
    }

    /** 집계 입력 한 줄. 실데이터/데모 경로가 공유해 산출 로직을 한 곳에 둔다. */
    private record ReelRow(
            int applicationId, int campaignId, String campaignTitle, String brandName,
            String campaignStatus, int creatorId, String creatorName, String reelUrl,
            ReelMetrics metrics, String source) {}

    // ── 실데이터 경로 ──────────────────────────────────

    /** 지표 출처 라벨: 자동 동기화 / 수동 입력 / 미연동. */
    private static final String SOURCE_NONE = "NONE";

    private List<ReelRow> realRows() {
        List<CampaignApplication> reels = applicationRepository.findBySubmissionUrlIsNotNull().stream()
                .filter(a -> a.getSubmissionUrl() != null && !a.getSubmissionUrl().isBlank())
                .toList();
        if (reels.isEmpty()) return List.of();

        Map<Integer, Campaign> campaignById = campaignRepository.findAllById(
                        reels.stream().map(CampaignApplication::getCampaignId).distinct().toList())
                .stream().collect(Collectors.toMap(Campaign::getId, c -> c));
        Map<Integer, Member> creatorById = memberRepository.findAllById(
                        reels.stream().map(CampaignApplication::getCreatorId).distinct().toList())
                .stream().collect(Collectors.toMap(Member::getId, m -> m));

        List<ReelRow> rows = new ArrayList<>(reels.size());
        for (CampaignApplication a : reels) {
            Campaign c = campaignById.get(a.getCampaignId());
            Member cr = creatorById.get(a.getCreatorId());
            Resolved resolved = resolveMetrics(a.getId());
            rows.add(new ReelRow(
                    a.getId(), a.getCampaignId(),
                    c != null ? c.getTitle() : "",
                    c != null ? c.getBrandName() : "",
                    c != null ? c.getStatus().name() : "",
                    a.getCreatorId(),
                    cr != null ? cr.getName() : "(알 수 없음)",
                    a.getSubmissionUrl(),
                    resolved.metrics(),
                    resolved.source()));
        }
        return rows;
    }

    /** 한 릴스의 지표 + 출처. 최신 스냅샷 → 수동(SubmissionMetric) 폴백 → 미연동(0). */
    private record Resolved(ReelMetrics metrics, String source) {}

    private Resolved resolveMetrics(int applicationId) {
        Optional<ReelMetricSnapshot> latest =
                snapshotRepository.findTopByApplicationIdOrderByCapturedAtDesc(applicationId);
        if (latest.isPresent()) {
            ReelMetricSnapshot s = latest.get();
            return new Resolved(
                    new ReelMetrics(s.getViews(), s.getLikes(), s.getComments(), s.getShares(),
                            snapshotTrend(applicationId)),
                    s.getSource().name()); // AUTO 또는 MANUAL
        }

        Optional<SubmissionMetric> manual = submissionMetricRepository.findByApplicationId(applicationId);
        if (manual.isPresent()) {
            SubmissionMetric m = manual.get();
            // 수동 입력에는 공유수가 없다 → shares 0, 추이 없음.
            return new Resolved(
                    new ReelMetrics(m.getViews(), m.getLikes(), m.getComments(), 0L, List.of()),
                    MetricSource.MANUAL.name());
        }

        return new Resolved(new ReelMetrics(0L, 0L, 0L, 0L, List.of()), SOURCE_NONE);
    }

    /**
     * 스냅샷 시계열의 연속 조회수 증가분을 길이 {@code TREND_DAYS} 배열로 구성(앞쪽 0 패딩, 최신 증가분이 끝).
     * 집계 단계가 인덱스별로 더하므로, 최근 증가분이 차트 우측(최신)에 오도록 우측 정렬한다.
     * 스냅샷이 2개 미만이면 증가분이 없어 전부 0(연동 초기).
     */
    private List<Long> snapshotTrend(int applicationId) {
        List<Long> padded = new ArrayList<>(TREND_DAYS);
        for (int i = 0; i < TREND_DAYS; i++) padded.add(0L);

        List<ReelMetricSnapshot> series =
                snapshotRepository.findByApplicationIdOrderByCapturedAtAsc(applicationId);
        if (series.size() < 2) return padded;

        List<Long> deltas = new ArrayList<>(series.size() - 1);
        for (int i = 1; i < series.size(); i++) {
            deltas.add(Math.max(0L, series.get(i).getViews() - series.get(i - 1).getViews()));
        }
        // 최근 TREND_DAYS 개만 우측 정렬해 배치.
        int take = Math.min(deltas.size(), TREND_DAYS);
        for (int i = 0; i < take; i++) {
            padded.set(TREND_DAYS - take + i, deltas.get(deltas.size() - take + i));
        }
        return padded;
    }

    // ── 집계 ──────────────────────────────────

    private ReelAnalyticsResponse aggregate(List<ReelRow> rows, int connectedCreators) {
        if (rows.isEmpty()) {
            return new ReelAnalyticsResponse(
                    new Summary(0, 0, 0L, 0L, 0L, 0L, 0.0),
                    List.of(), List.of(), List.of(), zeros(), connectedCreators);
        }

        long[] trend = new long[TREND_DAYS];
        long totalViews = 0, totalLikes = 0, totalComments = 0, totalShares = 0;
        double engagementSum = 0;
        List<ReelItem> allReels = new ArrayList<>();
        Map<Integer, long[]> creatorAgg = new HashMap<>();      // creatorId -> [views, likes, comments, count]
        Map<Integer, String> creatorName = new HashMap<>();
        Map<Integer, List<ReelItem>> itemsByCampaign = new LinkedHashMap<>();
        Map<Integer, ReelRow> metaByCampaign = new LinkedHashMap<>();

        for (ReelRow r : rows) {
            ReelMetrics m = r.metrics();
            long v = m.views(), l = m.likes(), co = m.comments(), sh = m.shares();
            double engagement = v > 0 ? (double) (l + co) / v : 0.0;
            totalViews += v; totalLikes += l; totalComments += co; totalShares += sh; engagementSum += engagement;

            List<Long> dv = m.dailyViews();
            if (dv != null) {
                for (int i = 0; i < Math.min(TREND_DAYS, dv.size()); i++) {
                    Long d = dv.get(i);
                    if (d != null) trend[i] += d;
                }
            }

            ReelItem item = new ReelItem(
                    r.applicationId(), r.campaignId(), r.campaignTitle(), r.creatorId(), r.creatorName(),
                    r.reelUrl(), v, l, co, sh, engagement, r.source());
            allReels.add(item);
            itemsByCampaign.computeIfAbsent(r.campaignId(), k -> new ArrayList<>()).add(item);
            metaByCampaign.putIfAbsent(r.campaignId(), r);

            long[] agg = creatorAgg.computeIfAbsent(r.creatorId(), k -> new long[4]);
            agg[0] += v; agg[1] += l; agg[2] += co; agg[3] += 1;
            creatorName.putIfAbsent(r.creatorId(), r.creatorName());
        }

        int activeCampaigns = 0;
        List<CampaignGroup> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<ReelItem>> e : itemsByCampaign.entrySet()) {
            ReelRow meta = metaByCampaign.get(e.getKey());
            List<ReelItem> items = e.getValue();
            if ("OPEN".equals(meta.campaignStatus())) activeCampaigns++;
            groups.add(new CampaignGroup(
                    e.getKey(), meta.campaignTitle(), meta.brandName(), meta.campaignStatus(),
                    items.size(),
                    items.stream().mapToLong(ReelItem::views).sum(),
                    items.stream().mapToLong(ReelItem::likes).sum(),
                    items.stream().mapToLong(ReelItem::comments).sum(),
                    items.stream().mapToLong(ReelItem::shares).sum(),
                    items));
        }
        groups.sort(Comparator.comparingLong(CampaignGroup::views).reversed());

        List<ReelItem> topReels = allReels.stream()
                .sorted(Comparator.comparingLong(ReelItem::views).reversed())
                .limit(TOP_N).toList();

        List<CreatorRank> topCreators = creatorAgg.entrySet().stream()
                .map(e -> {
                    long[] x = e.getValue();
                    return new CreatorRank(e.getKey(), creatorName.getOrDefault(e.getKey(), "(알 수 없음)"),
                            (int) x[3], x[0], x[1], x[2]);
                })
                .sorted(Comparator.comparingLong(CreatorRank::views).reversed())
                .limit(TOP_N).toList();

        List<Long> trendList = new ArrayList<>(TREND_DAYS);
        for (long t : trend) trendList.add(t);

        return new ReelAnalyticsResponse(
                new Summary(activeCampaigns, rows.size(), totalViews, totalLikes, totalComments,
                        totalShares, engagementSum / rows.size()),
                groups, topReels, topCreators, trendList, connectedCreators);
    }

    private static List<Long> zeros() {
        List<Long> z = new ArrayList<>(TREND_DAYS);
        for (int i = 0; i < TREND_DAYS; i++) z.add(0L);
        return z;
    }

    // ── 데모 데이터 (DB 미접근, analytics.demo-mode=true 일 때만) ──────────────────────────────────

    private List<ReelRow> demoRows() {
        // campaignId, 제목, 브랜드, 상태
        String[][] camps = {
                {"101", "여름 신상 화장품 챌린지", "글로우랩", "OPEN"},
                {"102", "신메뉴 먹방 릴스", "맘스키친", "OPEN"},
                {"103", "홈트 30일 루틴", "핏기어", "OPEN"},
                {"104", "가을 패션 룩북", "데일리원", "CLOSED"},
                {"105", "무선 이어버드 언박싱", "사운드웨이브", "OPEN"},
        };
        // 캠페인 인덱스, creatorId, 크리에이터명, 조회수, 좋아요, 댓글
        Object[][] reels = {
                {0, 201, "김서연", 1_820_000L, 142_000L, 5_300L},
                {0, 202, "이도윤", 940_000L, 71_000L, 2_100L},
                {0, 203, "박지우", 360_000L, 28_500L, 990L},
                {1, 204, "최민준", 4_510_000L, 305_000L, 12_400L},
                {1, 205, "정하윤", 1_120_000L, 88_000L, 3_600L},
                {1, 201, "김서연", 540_000L, 39_000L, 1_500L},
                {2, 206, "강시우", 280_000L, 19_000L, 720L},
                {2, 207, "윤서아", 760_000L, 58_000L, 2_400L},
                {2, 204, "최민준", 1_350_000L, 96_000L, 4_100L},
                {3, 208, "임준호", 210_000L, 13_500L, 540L},
                {3, 203, "박지우", 95_000L, 6_200L, 230L},
                {4, 205, "정하윤", 620_000L, 47_000L, 1_900L},
                {4, 207, "윤서아", 1_980_000L, 151_000L, 6_100L},
                {4, 206, "강시우", 430_000L, 31_000L, 1_250L},
        };

        List<ReelRow> rows = new ArrayList<>(reels.length);
        int appId = 9001;
        for (Object[] r : reels) {
            String[] c = camps[((Number) r[0]).intValue()];
            long views = ((Number) r[3]).longValue();
            long likes = ((Number) r[4]).longValue();
            long comments = ((Number) r[5]).longValue();
            long shares = Math.round(likes * 0.1); // 데모 공유수 ≈ 좋아요의 10%
            rows.add(new ReelRow(
                    appId, Integer.parseInt(c[0]), c[1], c[2], c[3],
                    ((Number) r[1]).intValue(), (String) r[2],
                    "https://www.instagram.com/reel/demo" + appId + "/",
                    new ReelMetrics(views, likes, comments, shares, demoDaily(views)),
                    "AUTO"));
            appId++;
        }
        return rows;
    }

    /** 총 조회수를 TREND_SHAPE 비율로 14일에 분배. 합계는 대략 조회수에 수렴(최근 유입 가정). */
    private static List<Long> demoDaily(long views) {
        double sum = 0;
        for (double s : TREND_SHAPE) sum += s;
        List<Long> out = new ArrayList<>(TREND_DAYS);
        for (double s : TREND_SHAPE) out.add(Math.round(views * s / sum));
        return out;
    }
}

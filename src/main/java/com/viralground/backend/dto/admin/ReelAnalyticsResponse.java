package com.viralground.backend.dto.admin;

import java.util.List;

/**
 * 관리자 릴스 분석 대시보드 응답.
 */
public record ReelAnalyticsResponse(
        Summary summary,
        List<CampaignGroup> campaigns,
        List<ReelItem> topReels,
        List<CreatorRank> topCreators,
        List<Long> viewsTrend, // 최근 14일 일별 조회수 합계(오래된→최신)
        int connectedCreators // 인스타 연동(CONNECTED) 크리에이터 수
) {
    /** 상단 KPI. */
    public record Summary(
            int activeCampaigns,
            int totalReels,
            long totalViews,
            long totalLikes,
            long totalComments,
            long totalShares,
            double avgEngagementRate) {}

    /** 캠페인별 릴스 묶음. */
    public record CampaignGroup(
            int campaignId,
            String title,
            String brandName,
            String status,
            int reelCount,
            long views,
            long likes,
            long comments,
            long shares,
            List<ReelItem> reels) {}

    /** 릴스 1건. */
    public record ReelItem(
            int applicationId,
            int campaignId,
            String campaignTitle,
            int creatorId,
            String creatorName,
            String reelUrl,
            long views,
            long likes,
            long comments,
            long shares,
            double engagementRate,
            String source) {} // 지표 출처: AUTO(자동 동기화) / MANUAL(수동 입력) / NONE(미연동)

    /** 크리에이터별 집계(랭킹). */
    public record CreatorRank(
            int creatorId,
            String creatorName,
            int reelCount,
            long views,
            long likes,
            long comments) {}
}

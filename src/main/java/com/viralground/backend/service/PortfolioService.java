package com.viralground.backend.service;

import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.Review;
import com.viralground.backend.entity.Role;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.ReviewRepository;
import com.viralground.backend.repository.SubmissionMetricRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final MemberRepository memberRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final CampaignRepository campaignRepository;
    private final ReviewRepository reviewRepository;
    private final SubmissionMetricRepository metricRepository;
    private final CreatorProfileRepository creatorProfileRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> getPortfolio(Integer creatorId) {
        Member creator = memberRepository.findById(creatorId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        // SETTLED application 만 포트폴리오로 노출
        List<CampaignApplication> settled = applicationRepository
                .findByCreatorIdOrderByAppliedAtDesc(creatorId).stream()
                .filter(a -> a.getStatus() == ApplicationStatus.SETTLED)
                .toList();

        Map<Integer, Campaign> campaignById = settled.isEmpty()
                ? Map.of()
                : campaignRepository.findAllById(
                        settled.stream().map(CampaignApplication::getCampaignId).distinct().toList())
                    .stream()
                    .collect(Collectors.toMap(Campaign::getId, c -> c));

        List<Map<String, Object>> items = settled.stream().map(a -> {
            Campaign c = campaignById.get(a.getCampaignId());
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("applicationId", a.getId());
            m.put("campaignId", a.getCampaignId());
            m.put("campaignTitle", c != null ? c.getTitle() : "");
            m.put("brandName", c != null ? c.getBrandName() : "");
            m.put("rewardPaidAmount", a.getRewardPaidAmount());
            m.put("videoFileKey", a.getVideoFileKey());
            m.put("settledAt", a.getSettledAt());
            return m;
        }).toList();

        // 크리에이터가 받은 리뷰 (기업이 크리에이터에게 남긴 것)
        List<Review> received = reviewRepository.findByTargetIdOrderByCreatedAtDesc(creatorId);
        double avgRating = received.isEmpty()
                ? 0.0
                : received.stream().mapToInt(Review::getRating).average().orElse(0.0);

        // 성과 집계: SETTLED 지원의 metric 합계. 기업이 승인 전 퍼포먼스를 가늠하는 핵심 근거.
        Object[] metricSum = metricRepository.sumByCreatorId(creatorId);
        long totalViews = metricSum.length > 0 && metricSum[0] instanceof Number n ? n.longValue() : 0L;
        long totalLikes = metricSum.length > 1 && metricSum[1] instanceof Number n ? n.longValue() : 0L;
        long totalComments = metricSum.length > 2 && metricSum[2] instanceof Number n ? n.longValue() : 0L;
        long sampleSize = metricSum.length > 3 && metricSum[3] instanceof Number n ? n.longValue() : 0L;
        long averageViews = sampleSize == 0 ? 0L : totalViews / sampleSize;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCompleted", settled.size());
        summary.put("reviewCount", received.size());
        summary.put("averageRating", Math.round(avgRating * 10) / 10.0);
        summary.put("totalViews", totalViews);
        summary.put("totalLikes", totalLikes);
        summary.put("totalComments", totalComments);
        summary.put("metricSampleSize", sampleSize);
        summary.put("averageViews", averageViews);

        Map<String, Object> creatorInfo = Map.of(
                "id", creator.getId(),
                "name", creator.getName(),
                "joinedAt", creator.getCreatedAt() != null ? creator.getCreatedAt() : LocalDateTime.now());

        return Map.of(
                "creator", creatorInfo,
                "summary", summary,
                "items", items);
    }

    /** 비로그인 공개 상세는 지급액과 내부 파일 키를 절대 노출하지 않는다. */
    @Transactional(readOnly = true)
    public Map<String, Object> getPublicPortfolio(Integer creatorId) {
        requirePublicProfileOptIn(creatorId);
        Member creator = memberRepository.findById(creatorId)
                .filter(m -> m.getRole() == Role.CREATOR && m.getStatus() == MemberStatus.APPROVED)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        List<CampaignApplication> settled = applicationRepository
                .findByCreatorIdOrderByAppliedAtDesc(creatorId).stream()
                .filter(a -> a.getStatus() == ApplicationStatus.SETTLED)
                .toList();
        Map<Integer, Campaign> campaignById = settled.isEmpty() ? Map.of() : campaignRepository
                .findAllById(settled.stream().map(CampaignApplication::getCampaignId).distinct().toList())
                .stream().collect(Collectors.toMap(Campaign::getId, c -> c));

        List<Map<String, Object>> items = settled.stream().map(a -> {
            Campaign campaign = campaignById.get(a.getCampaignId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("campaignTitle", campaign == null ? "" : campaign.getTitle());
            item.put("brandName", campaign == null ? "" : campaign.getBrandName());
            item.put("settledAt", a.getSettledAt());
            return item;
        }).toList();

        List<Review> reviews = reviewRepository.findByTargetIdOrderByCreatedAtDesc(creatorId);
        double averageRating = reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);
        Object[] metricSum = metricRepository.sumByCreatorId(creatorId);
        long totalViews = metricSum.length > 0 && metricSum[0] instanceof Number n ? n.longValue() : 0L;
        long totalLikes = metricSum.length > 1 && metricSum[1] instanceof Number n ? n.longValue() : 0L;
        long totalComments = metricSum.length > 2 && metricSum[2] instanceof Number n ? n.longValue() : 0L;
        long sampleSize = metricSum.length > 3 && metricSum[3] instanceof Number n ? n.longValue() : 0L;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCompleted", settled.size());
        summary.put("reviewCount", reviews.size());
        summary.put("averageRating", Math.round(averageRating * 10) / 10.0);
        summary.put("totalViews", totalViews);
        summary.put("totalLikes", totalLikes);
        summary.put("totalComments", totalComments);
        summary.put("metricSampleSize", sampleSize);
        summary.put("averageViews", sampleSize == 0 ? 0L : totalViews / sampleSize);

        return Map.of(
                "creator", Map.of(
                        "id", creator.getId(),
                        "name", creator.getName(),
                        "joinedAt", creator.getCreatedAt() != null ? creator.getCreatedAt() : LocalDateTime.now()),
                "summary", summary,
                "items", items);
    }

    private void requirePublicProfileOptIn(Integer creatorId) {
        creatorProfileRepository.findByMemberId(creatorId)
                .filter(profile -> Boolean.TRUE.equals(profile.getPublicProfileOptIn()))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }
}

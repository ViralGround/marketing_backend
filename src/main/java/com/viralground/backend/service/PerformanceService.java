package com.viralground.backend.service;

import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.Role;
import com.viralground.backend.entity.SubmissionMetric;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.SubmissionMetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PerformanceService {

    private final CampaignApplicationRepository applicationRepository;
    private final CampaignRepository campaignRepository;
    private final MemberRepository memberRepository;
    private final SubmissionMetricRepository metricRepository;

    /** 크리에이터 본인의 누적 성과 + 작품별 수치. */
    @Transactional(readOnly = true)
    public Map<String, Object> getCreatorPerformance(Integer creatorId) {
        List<CampaignApplication> settled = applicationRepository
                .findByCreatorIdOrderByAppliedAtDesc(creatorId).stream()
                .filter(a -> a.getStatus() == ApplicationStatus.SETTLED)
                .toList();

        Map<Integer, Campaign> campaignById = settled.isEmpty()
                ? Map.of()
                : campaignRepository.findAllById(
                        settled.stream().map(CampaignApplication::getCampaignId).distinct().toList())
                    .stream().collect(Collectors.toMap(Campaign::getId, c -> c));

        Map<Integer, SubmissionMetric> metricByAppId = settled.isEmpty()
                ? Map.of()
                : metricRepository.findByApplicationIdIn(
                        settled.stream().map(CampaignApplication::getId).toList())
                    .stream().collect(Collectors.toMap(SubmissionMetric::getApplicationId, m -> m));

        long totalViews = 0, totalLikes = 0, totalComments = 0;
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (CampaignApplication a : settled) {
            Campaign c = campaignById.get(a.getCampaignId());
            SubmissionMetric m = metricByAppId.get(a.getId());
            long v = m != null ? m.getViews() : 0;
            long l = m != null ? m.getLikes() : 0;
            long co = m != null ? m.getComments() : 0;
            totalViews += v; totalLikes += l; totalComments += co;

            Map<String, Object> item = new java.util.HashMap<>();
            item.put("applicationId", a.getId());
            item.put("campaignTitle", c != null ? c.getTitle() : "");
            item.put("brandName", c != null ? c.getBrandName() : "");
            item.put("views", v);
            item.put("likes", l);
            item.put("comments", co);
            item.put("externalUrl", m != null ? m.getExternalUrl() : null);
            item.put("recordedAt", m != null ? m.getRecordedAt() : null);
            items.add(item);
        }

        return Map.of(
                "totals", Map.of(
                        "views", totalViews,
                        "likes", totalLikes,
                        "comments", totalComments,
                        "completedCount", settled.size()),
                "items", items);
    }

    /** 기업이 자기 캠페인의 영상별 성과를 조회. ADMIN 은 모든 캠페인 접근. */
    @Transactional(readOnly = true)
    public Map<String, Object> getCampaignPerformance(Integer campaignId, Integer viewerId, Role viewerRole) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (viewerRole != Role.ADMIN && !campaign.getCreatedById().equals(viewerId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }

        List<CampaignApplication> apps = applicationRepository
                .findByCampaignIdOrderByAppliedAtDesc(campaignId);
        List<CampaignApplication> settled = apps.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.SETTLED)
                .toList();

        Map<Integer, Member> creatorById = settled.isEmpty()
                ? Map.of()
                : memberRepository.findAllById(
                        settled.stream().map(CampaignApplication::getCreatorId).distinct().toList())
                    .stream().collect(Collectors.toMap(Member::getId, m -> m));

        Map<Integer, SubmissionMetric> metricByAppId = settled.isEmpty()
                ? Map.of()
                : metricRepository.findByApplicationIdIn(
                        settled.stream().map(CampaignApplication::getId).toList())
                    .stream().collect(Collectors.toMap(SubmissionMetric::getApplicationId, m -> m));

        long totalViews = 0, totalLikes = 0, totalComments = 0;
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (CampaignApplication a : settled) {
            Member cr = creatorById.get(a.getCreatorId());
            SubmissionMetric m = metricByAppId.get(a.getId());
            long v = m != null ? m.getViews() : 0;
            long l = m != null ? m.getLikes() : 0;
            long co = m != null ? m.getComments() : 0;
            totalViews += v; totalLikes += l; totalComments += co;

            Map<String, Object> item = new java.util.HashMap<>();
            item.put("applicationId", a.getId());
            item.put("creatorId", a.getCreatorId());
            item.put("creatorName", cr != null ? cr.getName() : "(알 수 없음)");
            item.put("views", v);
            item.put("likes", l);
            item.put("comments", co);
            item.put("externalUrl", m != null ? m.getExternalUrl() : null);
            item.put("recordedAt", m != null ? m.getRecordedAt() : null);
            items.add(item);
        }

        return Map.of(
                "campaignId", campaign.getId(),
                "campaignTitle", campaign.getTitle(),
                "totals", Map.of(
                        "views", totalViews,
                        "likes", totalLikes,
                        "comments", totalComments,
                        "completedCount", settled.size()),
                "items", items);
    }
}

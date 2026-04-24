package com.viralground.backend.service;

import com.viralground.backend.dto.campaign.ApplicationResponse;
import com.viralground.backend.dto.campaign.CampaignResponse;
import com.viralground.backend.dto.campaign.SubmitWorkRequest;
import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final EmailService emailService;
    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public List<CampaignResponse> getOpenCampaigns(String sort, String search, Integer creatorId) {
        List<Campaign> campaigns = campaignRepository.findOpenCampaigns(
                search != null && !search.isBlank() ? search : null);
        if (campaigns.isEmpty()) return List.of();

        Map<Integer, CampaignApplication> myApps = creatorId != null
                ? applicationRepository.findByCreatorIdOrderByAppliedAtDesc(creatorId).stream()
                        .collect(Collectors.toMap(CampaignApplication::getCampaignId, a -> a, (a, b) -> a))
                : Map.of();

        List<Integer> ids = campaigns.stream().map(Campaign::getId).toList();
        Map<Integer, Long> countByCampaignId = applicationRepository.countByCampaignIdIn(ids).stream()
                .collect(Collectors.toMap(
                        CampaignApplicationRepository.CampaignCountRow::getCampaignId,
                        CampaignApplicationRepository.CampaignCountRow::getCount));

        Comparator<Campaign> comparator = switch (sort != null ? sort : "recent") {
            case "reward" -> Comparator.comparing(
                    Campaign::getRewardAmount, Comparator.nullsLast(Comparator.naturalOrder())).reversed();
            case "deadline" -> Comparator.comparing(
                    c -> c.getDeadline() != null ? c.getDeadline() : LocalDateTime.MAX);
            default -> Comparator.comparing(
                    Campaign::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed();
        };

        return campaigns.stream()
                .sorted(comparator)
                .map(c -> new CampaignResponse(
                        c,
                        myApps.get(c.getId()),
                        countByCampaignId.getOrDefault(c.getId(), 0L).intValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CampaignResponse getCampaign(Integer id, Integer creatorId) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        CampaignApplication myApp = creatorId != null
                ? applicationRepository.findByCampaignIdAndCreatorId(id, creatorId).orElse(null)
                : null;
        int count = applicationRepository.findByCampaignIdOrderByAppliedAtDesc(id).size();
        return new CampaignResponse(campaign, myApp, count);
    }

    @Transactional
    public CampaignApplication apply(Integer campaignId, Integer creatorId, String message) {
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));

        if (applicationRepository.existsByCampaignIdAndCreatorId(campaignId, creatorId)) {
            throw new AppException(ErrorCode.ALREADY_APPLIED);
        }

        CampaignApplication app = applicationRepository.save(CampaignApplication.builder()
                .campaignId(campaignId)
                .creatorId(creatorId)
                .message(message)
                .build());

        Campaign campaign = campaignRepository.findById(campaignId).orElseThrow();
        Member creator = memberRepository.findById(creatorId).orElseThrow();
        emailService.notifyAdminsOfNewApplication(campaign.getTitle(), creator.getName());

        return app;
    }

    public List<ApplicationResponse> getMyApplications(Integer creatorId, String statusStr) {
        ApplicationStatus status = null;
        if (statusStr != null && !"ALL".equals(statusStr)) {
            status = ApplicationStatus.valueOf(statusStr);
        }
        return applicationRepository.findByCreatorIdAndStatus(creatorId, status).stream()
                .map(ApplicationResponse::new)
                .toList();
    }

    @Transactional
    public void submitWork(Integer applicationId, Integer creatorId, SubmitWorkRequest request) {
        CampaignApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));

        if (!app.getCreatorId().equals(creatorId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }

        boolean hasFile = request != null && request.videoFileKey() != null && !request.videoFileKey().isBlank();
        boolean hasUrl = request != null && request.submissionUrl() != null && !request.submissionUrl().isBlank();
        if (!hasFile && !hasUrl) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }

        if (hasFile) {
            if (request.videoContentType() == null || request.videoContentType().isBlank()) {
                throw new AppException(ErrorCode.INVALID_VIDEO_FORMAT);
            }
            if (request.videoSizeBytes() == null || request.videoSizeBytes() <= 0) {
                throw new AppException(ErrorCode.VIDEO_TOO_LARGE);
            }
            app.setVideoFileKey(request.videoFileKey());
            app.setVideoContentType(request.videoContentType());
            app.setVideoSizeBytes(request.videoSizeBytes());
            app.setSubmissionUrl(null);
        } else {
            app.setSubmissionUrl(request.submissionUrl());
            app.setVideoFileKey(null);
            app.setVideoContentType(null);
            app.setVideoSizeBytes(null);
        }
        app.setStatus(ApplicationStatus.SUBMITTED);
        app.setSubmittedAt(LocalDateTime.now());
        applicationRepository.save(app);
    }

    @Transactional
    public void deleteAccount(Integer memberId) {
        memberRepository.deleteById(memberId);
    }

    public Map<String, Object> getStats(Integer creatorId) {
        long totalEarned = applicationRepository.sumRewardByCreatorId(creatorId);
        long completed = applicationRepository.countByCreatorIdAndStatus(creatorId, ApplicationStatus.SETTLED);
        long active = applicationRepository.countByCreatorIdAndStatus(creatorId, ApplicationStatus.APPROVED)
                + applicationRepository.countByCreatorIdAndStatus(creatorId, ApplicationStatus.SUBMITTED);
        List<ApplicationResponse> recent = applicationRepository
                .findByCreatorIdOrderByAppliedAtDesc(creatorId).stream()
                .limit(5)
                .map(ApplicationResponse::new)
                .toList();

        return Map.of(
                "totalEarned", totalEarned,
                "completedCount", completed,
                "activeCount", active,
                "recentApplications", recent
        );
    }
}

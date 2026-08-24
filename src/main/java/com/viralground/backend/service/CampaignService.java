package com.viralground.backend.service;

import com.viralground.backend.dto.campaign.ApplicationResponse;
import com.viralground.backend.dto.campaign.CampaignResponse;
import com.viralground.backend.dto.campaign.SubmitWorkRequest;
import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import com.viralground.backend.storage.FileStorage;
import com.viralground.backend.storage.UploadOwnershipService;
import com.viralground.backend.validation.PublicUrlPolicy;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CampaignService {

    private static final Set<ApplicationStatus> SUBMITTABLE = Set.of(
            ApplicationStatus.APPROVED,
            ApplicationStatus.SUBMITTED,
            ApplicationStatus.CHANGES_REQUESTED);

    private final CampaignRepository campaignRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final EmailService emailService;
    private final MemberRepository memberRepository;
    private final ApplicationSubmissionRepository submissionRepository;
    private final CreatorInstagramConnectionRepository connectionRepository;
    private final FileStorage fileStorage;
    private final Clock clock;
    private final UploadOwnershipService uploadOwnershipService;

    @Value("${features.uploads.enabled:false}")
    private boolean uploadsFeatureEnabled = false;

    @Value("${features.payments.enabled:false}")
    private boolean paymentsFeatureEnabled = false;

    /** 캠페인 마감 여부. deadline 이 null 이면 "마감 미정" 으로 보고 항상 활성으로 간주. */
    private boolean isPastDeadline(Campaign c) {
        return c.getDeadline() != null && LocalDateTime.now(clock).isAfter(c.getDeadline());
    }

    @Transactional(readOnly = true)
    public List<CampaignResponse> getOpenCampaigns(String sort, String search, Integer creatorId) {
        List<Campaign> campaigns = campaignRepository.findOpenCampaigns(
                search != null && !search.isBlank() ? search : null,
                LocalDateTime.now(clock));
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

        Comparator<Campaign> recentComparator = Comparator.comparing(
                Campaign::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed();
        Comparator<Campaign> comparator = switch (sort != null ? sort : "recent") {
            case "reward" -> paymentsFeatureEnabled
                    ? Comparator.comparing(Campaign::getRewardAmount,
                            Comparator.nullsLast(Comparator.naturalOrder())).reversed()
                    : recentComparator;
            case "deadline" -> Comparator.comparing(
                    c -> c.getDeadline() != null ? c.getDeadline() : LocalDateTime.MAX);
            default -> recentComparator;
        };

        return campaigns.stream()
                .sorted(comparator)
                .map(c -> new CampaignResponse(
                        c,
                        myApps.get(c.getId()),
                        countByCampaignId.getOrDefault(c.getId(), 0L).intValue(),
                        resolveThumbUrl(c),
                        paymentsFeatureEnabled))
                .toList();
    }

    private String resolveThumbUrl(Campaign c) {
        if (c.getThumbnailFileKey() != null && !c.getThumbnailFileKey().isBlank()) {
            return fileStorage.signedDownloadUrl(c.getThumbnailFileKey());
        }
        return c.getThumbnailUrl();
    }

    @Transactional(readOnly = true)
    public CampaignResponse getCampaign(Integer id, Integer creatorId) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        // 숨김 캠페인은 누구에게도 보이지 않게 — 정보 누출 방지 위해 NOT_FOUND 재사용.
        // 마감 지난 캠페인도 같은 정책: 상세 진입 불가 (직링크 차단).
        if (campaign.isHidden() || isPastDeadline(campaign)) {
            throw new AppException(ErrorCode.CAMPAIGN_NOT_FOUND);
        }
        CampaignApplication myApp = creatorId != null
                ? applicationRepository.findByCampaignIdAndCreatorId(id, creatorId).orElse(null)
                : null;
        int count = (int) applicationRepository.countByCampaignId(id);
        return new CampaignResponse(campaign, myApp, count, resolveThumbUrl(campaign), paymentsFeatureEnabled);
    }

    @Transactional
    public CampaignApplication apply(Integer campaignId, Integer creatorId, String message) {
        Member creator = memberRepository.findById(creatorId)
                .orElseThrow(() -> new AppException(ErrorCode.FORBIDDEN));
        if (creator.getRole() != Role.CREATOR || creator.getStatus() != MemberStatus.APPROVED) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }

        Campaign target = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (target.isHidden()) {
            throw new AppException(ErrorCode.CAMPAIGN_NOT_FOUND);
        }
        if (target.getStatus() != CampaignStatus.OPEN) {
            throw new AppException(ErrorCode.CAMPAIGN_CLOSED);
        }
        boolean financiallyEligible = paymentsFeatureEnabled
                && target.getEscrowStatus() == EscrowStatus.FUNDED;
        boolean managedBetaEligible = !paymentsFeatureEnabled
                && target.getEscrowStatus() == EscrowStatus.NONE;
        if (!financiallyEligible && !managedBetaEligible) {
            throw new AppException(ErrorCode.CAMPAIGN_NOT_FUNDED);
        }
        if (isPastDeadline(target)) {
            throw new AppException(ErrorCode.CAMPAIGN_CLOSED);
        }

        if (applicationRepository.existsByCampaignIdAndCreatorId(campaignId, creatorId)) {
            throw new AppException(ErrorCode.ALREADY_APPLIED);
        }

        long currentApplications = applicationRepository.countByCampaignId(campaignId);
        if (currentApplications >= target.getMaxParticipants()) {
            throw new AppException(ErrorCode.CAMPAIGN_FULL);
        }

        CampaignApplication app = applicationRepository.save(CampaignApplication.builder()
                .campaignId(campaignId)
                .creatorId(creatorId)
                .message(message)
                .build());

        emailService.notifyAdminsOfNewApplication(target.getTitle(), creator.getName());

        return app;
    }

    public List<ApplicationResponse> getMyApplications(Integer creatorId, String statusStr) {
        if ("COMPLETED".equals(statusStr)) {
            return applicationRepository.findByCreatorIdOrderByAppliedAtDesc(creatorId).stream()
                    .filter(CampaignApplication::isCompletedWork)
                    .map(a -> new ApplicationResponse(a, paymentsFeatureEnabled))
                    .toList();
        }
        ApplicationStatus status = null;
        if (statusStr != null && !"ALL".equals(statusStr)) {
            status = ApplicationStatus.valueOf(statusStr);
        }
        return applicationRepository.findByCreatorIdAndStatus(creatorId, status).stream()
                .map(a -> new ApplicationResponse(a, paymentsFeatureEnabled))
                .toList();
    }

    /**
     * 작업물 제출. 반환값은 추적 모드: 크리에이터가 인스타 연동(CONNECTED)이면 {@code "AUTO"}(자동 지표 수집 대상),
     * 미연동이면 {@code "MANUAL"}(수동 입력). 연동 여부와 무관하게 제출 자체는 막지 않는다.
     */
    @Transactional
    public String submitWork(Integer applicationId, Integer creatorId, SubmitWorkRequest request) {
        CampaignApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));

        if (!app.getCreatorId().equals(creatorId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }

        // 제출은 APPROVED(최초) / SUBMITTED(검토 전 수정) / CHANGES_REQUESTED(재제출) 에서만 가능.
        // PENDING(검토 전), REJECTED, SETTLED 상태에서는 허용하지 않는다.
        if (!SUBMITTABLE.contains(app.getStatus())) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }
        if (app.getContentApprovedAt() != null) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }

        boolean hasFile = request != null && request.videoFileKey() != null && !request.videoFileKey().isBlank();
        boolean hasUrl = request != null && request.submissionUrl() != null && !request.submissionUrl().isBlank();
        if (!hasFile && !hasUrl) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }

        String normalizedSubmissionUrl = null;
        if (hasUrl) {
            try {
                normalizedSubmissionUrl = PublicUrlPolicy.normalizeRequired(request.submissionUrl());
            } catch (AppException invalidPublicUrl) {
                throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
            }
        }

        if (hasFile) {
            requireUploadsEnabled();
            if (request.videoContentType() == null || request.videoContentType().isBlank()) {
                throw new AppException(ErrorCode.INVALID_VIDEO_FORMAT);
            }
            if (request.videoSizeBytes() == null || request.videoSizeBytes() <= 0) {
                throw new AppException(ErrorCode.VIDEO_TOO_LARGE);
            }
            // presign 만 받고 실제 업로드를 누락했거나 타인의 키를 추측한 케이스 차단.
            // 현 단계에서는 파일 존재 여부만 검사한다 (업로드 주체 귀속은 추후 업로드 이력 테이블로 강화).
            if (!fileStorage.exists(request.videoFileKey())) {
                throw new AppException(ErrorCode.SUBMISSION_NOT_FOUND);
            }
            uploadOwnershipService.requireOwnedUpload(request.videoFileKey(), creatorId);
            app.setVideoFileKey(request.videoFileKey());
            app.setVideoContentType(request.videoContentType());
            app.setVideoSizeBytes(request.videoSizeBytes());
            app.setSubmissionUrl(null);
        } else {
            app.setSubmissionUrl(normalizedSubmissionUrl);
            app.setVideoFileKey(null);
            app.setVideoContentType(null);
            app.setVideoSizeBytes(null);
        }

        // 마감 지난 캠페인에는 신규 제출/재제출 모두 차단.
        // 입력 형식 검증을 먼저 통과시켜야 클라이언트가 의미있는 에러를 받는다.
        Campaign campaign = campaignRepository.findById(app.getCampaignId())
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (isPastDeadline(campaign)) {
            throw new AppException(ErrorCode.CAMPAIGN_CLOSED);
        }

        if (app.getStatus() == ApplicationStatus.CHANGES_REQUESTED) {
            app.setResubmissionCount((app.getResubmissionCount() == null ? 0 : app.getResubmissionCount()) + 1);
        }
        app.setReviewComment(null);
        app.setStatus(ApplicationStatus.SUBMITTED);
        app.setSubmittedAt(LocalDateTime.now(clock));
        applicationRepository.save(app);

        submissionRepository.save(ApplicationSubmission.builder()
                .applicationId(app.getId())
                .videoFileKey(app.getVideoFileKey())
                .videoContentType(app.getVideoContentType())
                .videoSizeBytes(app.getVideoSizeBytes())
                .submissionUrl(app.getSubmissionUrl())
                .status(SubmissionReviewStatus.SUBMITTED)
                .build());

        // 인스타 연동된 크리에이터의 제출물은 자동 추적(동기화가 지표를 채움), 미연동은 수동 추적.
        boolean connected = connectionRepository.findByCreatorId(creatorId)
                .map(c -> c.getStatus() == ConnectionStatus.CONNECTED)
                .orElse(false);
        return connected ? "AUTO" : "MANUAL";
    }

    private void requireUploadsEnabled() {
        if (!uploadsFeatureEnabled) throw new AppException(ErrorCode.UPLOAD_FEATURE_DISABLED);
    }

    public Map<String, Object> getStats(Integer creatorId) {
        long totalEarned = paymentsFeatureEnabled
                ? applicationRepository.sumRewardByCreatorId(creatorId)
                : 0L;
        long completed = applicationRepository.countCompletedByCreatorId(creatorId);
        long active = applicationRepository.countByCreatorIdAndStatus(creatorId, ApplicationStatus.APPROVED)
                + applicationRepository.countByCreatorIdAndStatus(creatorId, ApplicationStatus.SUBMITTED);
        List<ApplicationResponse> recent = applicationRepository
                .findByCreatorIdOrderByAppliedAtDesc(creatorId).stream()
                .limit(5)
                .map(a -> new ApplicationResponse(a, paymentsFeatureEnabled))
                .toList();

        Map<String, Object> stats = new LinkedHashMap<>();
        if (paymentsFeatureEnabled) stats.put("totalEarned", totalEarned);
        stats.put("completedCount", completed);
        stats.put("activeCount", active);
        stats.put("recentApplications", recent);
        return stats;
    }
}

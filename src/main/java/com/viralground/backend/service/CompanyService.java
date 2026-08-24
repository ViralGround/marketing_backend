package com.viralground.backend.service;

import com.viralground.backend.dto.company.CompanyApplicationActionRequest;
import com.viralground.backend.dto.company.CompanyCampaignCreateRequest;
import com.viralground.backend.dto.company.CompanyCampaignResponse;
import com.viralground.backend.dto.company.CompanyCampaignUpdateRequest;
import com.viralground.backend.dto.company.CompanyProfileResponse;
import com.viralground.backend.dto.company.UpdateCompanyProfileRequest;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.CompanyProfile;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.entity.Member;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.event.ApplicationResultEvent;
import com.viralground.backend.entity.ApplicationSubmission;
import com.viralground.backend.entity.SubmissionReviewStatus;
import com.viralground.backend.repository.ApplicationSubmissionRepository;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.EscrowTransactionRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.storage.FileStorage;
import com.viralground.backend.storage.UploadOwnershipService;
import com.viralground.backend.payment.PaymentActor;
import com.viralground.backend.validation.CampaignBudgetPolicy;
import com.viralground.backend.validation.PublicUrlPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CampaignRepository campaignRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final MemberRepository memberRepository;
    private final EscrowService escrowService;
    private final EscrowTransactionRepository escrowTransactionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationSubmissionRepository submissionRepository;
    private final FileStorage fileStorage;
    private final UploadOwnershipService uploadOwnershipService;
    private final CompanyProfileRepository companyProfileRepository;

    @Value("${features.uploads.enabled:false}")
    private boolean uploadsFeatureEnabled = false;

    @Value("${features.payments.enabled:false}")
    private boolean paymentsFeatureEnabled = false;

    private String resolveThumbUrl(Campaign c) {
        if (c.getThumbnailFileKey() != null && !c.getThumbnailFileKey().isBlank()) {
            return fileStorage.signedDownloadUrl(c.getThumbnailFileKey());
        }
        return c.getThumbnailUrl();
    }

    private String resolveLogoUrl(CompanyProfile p) {
        if (p.getLogoFileKey() != null && !p.getLogoFileKey().isBlank()) {
            return fileStorage.signedDownloadUrl(p.getLogoFileKey());
        }
        return null;
    }

    private String resolveVideoUrl(String fileKey) {
        return fileKey == null || fileKey.isBlank()
                ? null : fileStorage.signedDownloadUrl(fileKey);
    }

    @Transactional(readOnly = true)
    public CompanyProfileResponse getMyProfile(Integer memberId) {
        CompanyProfile p = companyProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return new CompanyProfileResponse(
                p.getCompanyName(),
                p.getIndustry(),
                p.getHomepage(),
                p.getIntroduction(),
                resolveLogoUrl(p));
    }

    /** 기업 본인 프로필 수정. 전달된 필드만 갱신하고, logoFileKey 빈 문자열은 로고 제거로 본다. */
    @Transactional
    public void updateMyProfile(Integer memberId, UpdateCompanyProfileRequest req) {
        CompanyProfile p = companyProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        String normalizedHomepage = req.getHomepage() == null
                ? null : PublicUrlPolicy.normalizeOptional(req.getHomepage());
        if (req.getLogoFileKey() != null) {
            if (!req.getLogoFileKey().isBlank()) {
                requireUploadsEnabled();
                uploadOwnershipService.requireOwnedUpload(req.getLogoFileKey(), memberId);
                if (!fileStorage.exists(req.getLogoFileKey())) {
                    throw new AppException(ErrorCode.SUBMISSION_NOT_FOUND);
                }
            }
        }

        if (req.getIntroduction() != null) p.setIntroduction(req.getIntroduction());
        if (req.getIndustry() != null) p.setIndustry(req.getIndustry());
        if (req.getHomepage() != null) p.setHomepage(normalizedHomepage);
        if (req.getLogoFileKey() != null) {
            p.setLogoFileKey(req.getLogoFileKey().isBlank() ? null : req.getLogoFileKey());
        }
        companyProfileRepository.save(p);
    }

    @Transactional
    public CompanyCampaignResponse createCampaign(Integer companyMemberId, CompanyCampaignCreateRequest req) {
        int totalBudget = CampaignBudgetPolicy.totalBudget(
                req.getRewardAmount(), req.getMaxParticipants());

        String thumbnailFileKey = req.getThumbnailFileKey();
        if (thumbnailFileKey != null && thumbnailFileKey.isBlank()) {
            thumbnailFileKey = null;
        }
        if (thumbnailFileKey != null) {
            requireUploadsEnabled();
            uploadOwnershipService.requireOwnedUpload(thumbnailFileKey, companyMemberId);
            if (!fileStorage.exists(thumbnailFileKey)) {
                throw new AppException(ErrorCode.SUBMISSION_NOT_FOUND);
            }
        }

        Campaign saved = campaignRepository.save(Campaign.builder()
                .title(req.getTitle())
                .description(req.getDescription())
                .brandName(req.getBrandName())
                .rewardAmount(req.getRewardAmount())
                .maxParticipants(req.getMaxParticipants())
                .totalBudget(totalBudget)
                .thumbnailFileKey(thumbnailFileKey)
                .requirements(req.getRequirements())
                .deadline(req.getDeadline())
                .status(CampaignStatus.DRAFT)
                .escrowStatus(paymentsFeatureEnabled
                        ? EscrowStatus.PENDING_DEPOSIT
                        : EscrowStatus.NONE)
                .createdById(companyMemberId)
                .build());

        return new CompanyCampaignResponse(saved, 0, resolveThumbUrl(saved));
    }

    public List<CompanyCampaignResponse> listCampaigns(Integer companyMemberId) {
        List<Campaign> campaigns = campaignRepository.findByCreatedByIdOrderByCreatedAtDesc(companyMemberId);
        if (campaigns.isEmpty()) return List.of();

        List<Integer> ids = campaigns.stream().map(Campaign::getId).toList();
        Map<Integer, Long> countByCampaignId = applicationRepository.countByCampaignIdIn(ids).stream()
                .collect(Collectors.toMap(
                        CampaignApplicationRepository.CampaignCountRow::getCampaignId,
                        CampaignApplicationRepository.CampaignCountRow::getCount));

        return campaigns.stream()
                .map(c -> new CompanyCampaignResponse(c,
                        countByCampaignId.getOrDefault(c.getId(), 0L).intValue(),
                        resolveThumbUrl(c)))
                .toList();
    }

    @Transactional(readOnly = true)
    public CompanyCampaignResponse getCampaign(Integer campaignId, Integer companyMemberId) {
        Campaign c = loadOwned(campaignId, companyMemberId);

        List<CampaignApplication> apps = applicationRepository.findByCampaignIdOrderByAppliedAtDesc(c.getId());

        Map<Integer, Member> creatorById = apps.isEmpty()
                ? Map.of()
                : memberRepository.findAllById(
                        apps.stream().map(CampaignApplication::getCreatorId).distinct().toList())
                    .stream()
                    .collect(Collectors.toMap(Member::getId, m -> m));

        List<Integer> appIds = apps.stream().map(CampaignApplication::getId).toList();
        Map<Integer, List<CompanyCampaignResponse.CompanySubmissionItem>> historyByAppId = appIds.isEmpty()
                ? Map.of()
                : submissionRepository
                        .findByApplicationIdInOrderByApplicationIdAscSubmittedAtAsc(appIds).stream()
                        .collect(Collectors.groupingBy(
                                ApplicationSubmission::getApplicationId,
                                Collectors.mapping(s -> new CompanyCampaignResponse.CompanySubmissionItem(
                                        s.getId(),
                                        resolveVideoUrl(s.getVideoFileKey()),
                                        s.getVideoContentType(),
                                        s.getVideoSizeBytes(),
                                        s.getSubmissionUrl(),
                                        s.getStatus(),
                                        s.getReviewComment(),
                                        s.getSubmittedAt(),
                                        s.getReviewedAt()), Collectors.toList())));

        List<CompanyCampaignResponse.ApplicationItem> applicationItems = apps.stream()
                .map(a -> {
                    Member cr = creatorById.get(a.getCreatorId());
                    CompanyCampaignResponse.CreatorInfo info = cr == null
                            ? new CompanyCampaignResponse.CreatorInfo(a.getCreatorId(), "(알 수 없음)")
                            : new CompanyCampaignResponse.CreatorInfo(cr.getId(), cr.getName());
                    List<CompanyCampaignResponse.CompanySubmissionItem> history =
                            historyByAppId.getOrDefault(a.getId(), List.of());
                    return new CompanyCampaignResponse.ApplicationItem(
                            a.getId(),
                            a.getApiStatus(),
                            a.getMessage(),
                            a.getSubmissionUrl(),
                            resolveVideoUrl(a.getVideoFileKey()),
                            a.getResubmissionCount(),
                            a.getReviewComment(),
                            a.getRewardPaidAmount(),
                            a.getAppliedAt(),
                            a.getSubmittedAt(),
                            a.getSettledAt(),
                            info,
                            history);
                })
                .toList();

        List<CompanyCampaignResponse.EscrowTransactionItem> escrowItems =
                escrowTransactionRepository.findByCampaignIdOrderByCreatedAtDesc(c.getId()).stream()
                        .map(tx -> new CompanyCampaignResponse.EscrowTransactionItem(
                                tx.getId(),
                                tx.getType(),
                                tx.getAmount(),
                                tx.getMemo(),
                                tx.getCreatedAt()))
                        .toList();

        return new CompanyCampaignResponse(c, apps.size(), applicationItems, escrowItems, resolveThumbUrl(c));
    }

    public Map<String, Object> getDashboardSummary(Integer companyMemberId) {
        List<Campaign> campaigns = campaignRepository.findByCreatedByIdOrderByCreatedAtDesc(companyMemberId);
        Map<String, Object> result = new HashMap<>();
        result.put("totalCampaigns", campaigns.size());
        result.put("pendingDeposit", campaigns.stream().filter(c -> c.getEscrowStatus() == EscrowStatus.PENDING_DEPOSIT).count());
        result.put("depositConfirming", campaigns.stream().filter(c -> c.getEscrowStatus() == EscrowStatus.DEPOSIT_CONFIRMING).count());
        result.put("funded", campaigns.stream().filter(c -> c.getStatus() == CampaignStatus.OPEN).count());
        result.put("closed", campaigns.stream().filter(c -> c.getStatus() == CampaignStatus.CLOSED).count());
        return result;
    }

    @Transactional
    public CompanyCampaignResponse updateCampaign(Integer campaignId, Integer companyMemberId, CompanyCampaignUpdateRequest req) {
        Campaign c = loadOwned(campaignId, companyMemberId);
        requireNonFinancialCampaignWhenPaymentsDisabled(c);

        if (c.getStatus() == CampaignStatus.CLOSED) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }

        if (req.getThumbnailFileKey() != null) {
            if (!req.getThumbnailFileKey().isBlank()) {
                requireUploadsEnabled();
                uploadOwnershipService.requireOwnedUpload(
                        req.getThumbnailFileKey(), companyMemberId);
                if (!fileStorage.exists(req.getThumbnailFileKey())) {
                    throw new AppException(ErrorCode.SUBMISSION_NOT_FOUND);
                }
            }
        }

        boolean budgetFieldsIncluded = req.getRewardAmount() != null || req.getMaxParticipants() != null;
        Integer rewardAmount = c.getRewardAmount();
        Integer maxParticipants = c.getMaxParticipants();
        Integer totalBudget = null;
        if (budgetFieldsIncluded) {
            if (!isBudgetEditable(c)) {
                throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
            }
            rewardAmount = req.getRewardAmount() != null
                    ? req.getRewardAmount() : c.getRewardAmount();
            maxParticipants = req.getMaxParticipants() != null
                    ? req.getMaxParticipants() : c.getMaxParticipants();
            totalBudget = CampaignBudgetPolicy.totalBudget(rewardAmount, maxParticipants);
        }

        if (req.getTitle() != null) c.setTitle(req.getTitle());
        if (req.getDescription() != null) c.setDescription(req.getDescription());
        if (req.getBrandName() != null) c.setBrandName(req.getBrandName());
        if (req.getThumbnailFileKey() != null) {
            c.setThumbnailFileKey(req.getThumbnailFileKey().isBlank() ? null : req.getThumbnailFileKey());
        }
        if (req.getRequirements() != null) c.setRequirements(req.getRequirements());
        if (req.getDeadline() != null) c.setDeadline(req.getDeadline());
        if (budgetFieldsIncluded) {
            c.setRewardAmount(rewardAmount);
            c.setMaxParticipants(maxParticipants);
            c.setTotalBudget(totalBudget);
        }

        Campaign saved = campaignRepository.save(c);
        return new CompanyCampaignResponse(saved,
                (int) applicationRepository.countByCampaignId(saved.getId()),
                resolveThumbUrl(saved));
    }

    private void requireUploadsEnabled() {
        if (!uploadsFeatureEnabled) throw new AppException(ErrorCode.UPLOAD_FEATURE_DISABLED);
    }

    @Transactional
    public void deleteCampaign(Integer campaignId, Integer companyMemberId) {
        Campaign c = loadOwned(campaignId, companyMemberId);
        requireNonFinancialCampaignWhenPaymentsDisabled(c);
        if (c.getStatus() != CampaignStatus.DRAFT || !isPreFinancial(c)) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }
        if (applicationRepository.countByCampaignId(campaignId) > 0) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }
        campaignRepository.delete(c);
    }

    @Transactional
    public void cancelCampaign(Integer campaignId, Integer companyMemberId) {
        Campaign c = loadOwned(campaignId, companyMemberId);
        requireNonFinancialCampaignWhenPaymentsDisabled(c);
        if (applicationRepository.countByCampaignId(campaignId) > 0) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }
        EscrowStatus es = c.getEscrowStatus();
        if (es != EscrowStatus.NONE && es != EscrowStatus.PENDING_DEPOSIT
                && es != EscrowStatus.DEPOSIT_CONFIRMING && es != EscrowStatus.FUNDED) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }

        if (es == EscrowStatus.FUNDED) {
            escrowService.refund(
                    campaignId, PaymentActor.company(companyMemberId), "기업 캠페인 취소 환불",
                    "refund:campaign:" + campaignId);
        } else if (es != EscrowStatus.NONE) {
            c.setEscrowStatus(EscrowStatus.REFUNDED);
            c.setRefundedAt(LocalDateTime.now());
        }
        c.setStatus(CampaignStatus.CLOSED);
        campaignRepository.save(c);
    }

    @Transactional
    public void manageApplication(Integer applicationId, Integer companyMemberId,
                                  CompanyApplicationActionRequest req) {
        CampaignApplication app = applicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));
        Campaign campaign = campaignRepository.findById(app.getCampaignId())
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (!campaign.getCreatedById().equals(companyMemberId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        requireNonFinancialCampaignWhenPaymentsDisabled(campaign);

        switch (req.getAction()) {
            case APPROVE -> {
                if (app.getStatus() != ApplicationStatus.PENDING) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                app.setStatus(ApplicationStatus.APPROVED);
                app.setReviewedAt(LocalDateTime.now());
                publishApplicationResult(app, campaign, "APPROVED", null, null);
            }
            case REJECT -> {
                if (app.getStatus() != ApplicationStatus.PENDING) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                app.setStatus(ApplicationStatus.REJECTED);
                app.setReviewedAt(LocalDateTime.now());
                publishApplicationResult(app, campaign, "REJECTED", null, null);
            }
            case APPROVE_VIDEO -> {
                if (!paymentsFeatureEnabled) {
                    throw new AppException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
                }
                if (app.getStatus() != ApplicationStatus.SUBMITTED
                        || app.getContentApprovedAt() != null) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                int payout = req.getRewardPaidAmount() != null
                        ? req.getRewardPaidAmount()
                        : campaign.getRewardAmount();
                if (payout <= 0 || payout > campaign.getRewardAmount()) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                escrowService.release(
                        campaign.getId(), app.getId(), payout,
                        PaymentActor.company(companyMemberId), "기업 콘텐츠 승인 및 정산",
                        "release:campaign:" + campaign.getId() + ":application:" + app.getId());
                app.setStatus(ApplicationStatus.SETTLED);
                app.setRewardPaidAmount(payout);
                app.setSettledAt(LocalDateTime.now());
                app.setReviewedAt(LocalDateTime.now());
                markLatestSubmission(app.getId(), companyMemberId,
                        SubmissionReviewStatus.APPROVED, null);
                publishApplicationResult(app, campaign, "SETTLED", payout, null);
            }
            case APPROVE_CONTENT -> {
                if (paymentsFeatureEnabled) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                if (campaign.getEscrowStatus() != EscrowStatus.NONE
                        || app.getStatus() != ApplicationStatus.SUBMITTED
                        || app.getContentApprovedAt() != null) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                // Persist the old-backend terminal enum so rollback cannot
                // re-process this content. The marker makes the new API expose
                // COMPLETED without implying a payment settlement.
                app.setStatus(ApplicationStatus.SETTLED);
                app.setContentApprovedAt(LocalDateTime.now());
                app.setRewardPaidAmount(null);
                app.setSettledAt(null);
                app.setReviewedAt(LocalDateTime.now());
                markLatestSubmission(app.getId(), companyMemberId,
                        SubmissionReviewStatus.APPROVED, null);
                publishApplicationResult(app, campaign, "COMPLETED", null, null);
            }
            case REQUEST_CHANGES -> {
                if (app.getStatus() != ApplicationStatus.SUBMITTED) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                String comment = req.getReviewComment();
                if (comment == null || comment.isBlank()) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                app.setStatus(ApplicationStatus.CHANGES_REQUESTED);
                app.setReviewComment(comment);
                app.setReviewedAt(LocalDateTime.now());
                markLatestSubmission(app.getId(), companyMemberId,
                        SubmissionReviewStatus.CHANGES_REQUESTED, comment);
                publishApplicationResult(app, campaign, "CHANGES_REQUESTED", null, comment);
            }
            case REJECT_VIDEO -> {
                if (app.getStatus() != ApplicationStatus.SUBMITTED) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                app.setStatus(ApplicationStatus.REJECTED);
                app.setReviewComment(req.getReviewComment());
                app.setReviewedAt(LocalDateTime.now());
                markLatestSubmission(app.getId(), companyMemberId,
                        SubmissionReviewStatus.REJECTED, req.getReviewComment());
                publishApplicationResult(app, campaign, "REJECTED", null, req.getReviewComment());
            }
        }

        applicationRepository.save(app);
    }

    /** Publish a draft in managed beta without creating or mutating payment records. */
    @Transactional
    public void publishManagedBetaCampaign(Integer campaignId, Integer companyMemberId) {
        if (paymentsFeatureEnabled) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }
        Campaign campaign = loadOwned(campaignId, companyMemberId);
        if (campaign.getStatus() != CampaignStatus.DRAFT
                || campaign.getEscrowStatus() != EscrowStatus.NONE) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }
        if (campaign.getDeadline() != null && !campaign.getDeadline().isAfter(LocalDateTime.now())) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }
        campaign.setStatus(CampaignStatus.OPEN);
        campaignRepository.save(campaign);
    }

    private boolean isPreFinancial(Campaign campaign) {
        return campaign.getEscrowStatus() == EscrowStatus.PENDING_DEPOSIT
                || (!paymentsFeatureEnabled && campaign.getEscrowStatus() == EscrowStatus.NONE);
    }

    private boolean isBudgetEditable(Campaign campaign) {
        return campaign.getStatus() == CampaignStatus.DRAFT && isPreFinancial(campaign);
    }

    private void requireNonFinancialCampaignWhenPaymentsDisabled(Campaign campaign) {
        if (!paymentsFeatureEnabled && campaign.getEscrowStatus() != EscrowStatus.NONE) {
            throw new AppException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
    }

    private void markLatestSubmission(Integer applicationId, Integer reviewerId,
                                      SubmissionReviewStatus status, String comment) {
        submissionRepository.findTopByApplicationIdOrderBySubmittedAtDesc(applicationId)
                .ifPresent(sub -> {
                    sub.setStatus(status);
                    sub.setReviewerId(reviewerId);
                    sub.setReviewComment(comment);
                    sub.setReviewedAt(LocalDateTime.now());
                    submissionRepository.save(sub);
                });
    }

    private void publishApplicationResult(CampaignApplication app, Campaign campaign,
                                          String status, Integer rewardAmount, String reviewComment) {
        memberRepository.findById(app.getCreatorId()).ifPresent((Member creator) ->
                eventPublisher.publishEvent(new ApplicationResultEvent(
                        creator.getEmail(),
                        creator.getName(),
                        campaign.getTitle(),
                        status,
                        rewardAmount,
                        reviewComment
                ))
        );
    }

    private Campaign loadOwned(Integer campaignId, Integer companyMemberId) {
        Campaign c = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (!c.getCreatedById().equals(companyMemberId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return c;
    }
}

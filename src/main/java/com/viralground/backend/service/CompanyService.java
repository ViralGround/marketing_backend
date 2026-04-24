package com.viralground.backend.service;

import com.viralground.backend.dto.campaign.SubmissionHistoryItem;
import com.viralground.backend.dto.company.CompanyApplicationActionRequest;
import com.viralground.backend.dto.company.CompanyCampaignCreateRequest;
import com.viralground.backend.dto.company.CompanyCampaignResponse;
import com.viralground.backend.dto.company.CompanyCampaignUpdateRequest;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.CampaignStatus;
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
import com.viralground.backend.repository.EscrowTransactionRepository;
import com.viralground.backend.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
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

    @Transactional
    public CompanyCampaignResponse createCampaign(Integer companyMemberId, CompanyCampaignCreateRequest req) {
        if (req.getRewardAmount() == null || req.getMaxParticipants() == null) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }
        int totalBudget = req.getRewardAmount() * req.getMaxParticipants();

        Campaign saved = campaignRepository.save(Campaign.builder()
                .title(req.getTitle())
                .description(req.getDescription())
                .brandName(req.getBrandName())
                .rewardAmount(req.getRewardAmount())
                .maxParticipants(req.getMaxParticipants())
                .totalBudget(totalBudget)
                .thumbnailUrl(req.getThumbnailUrl())
                .requirements(req.getRequirements())
                .deadline(req.getDeadline())
                .status(CampaignStatus.DRAFT)
                .escrowStatus(EscrowStatus.PENDING_DEPOSIT)
                .createdById(companyMemberId)
                .build());

        return new CompanyCampaignResponse(saved, 0);
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
                        countByCampaignId.getOrDefault(c.getId(), 0L).intValue()))
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
        Map<Integer, List<SubmissionHistoryItem>> historyByAppId = appIds.isEmpty()
                ? Map.of()
                : submissionRepository
                        .findByApplicationIdInOrderByApplicationIdAscSubmittedAtAsc(appIds).stream()
                        .collect(Collectors.groupingBy(
                                ApplicationSubmission::getApplicationId,
                                Collectors.mapping(SubmissionHistoryItem::from, Collectors.toList())));

        List<CompanyCampaignResponse.ApplicationItem> applicationItems = apps.stream()
                .map(a -> {
                    Member cr = creatorById.get(a.getCreatorId());
                    CompanyCampaignResponse.CreatorInfo info = cr == null
                            ? new CompanyCampaignResponse.CreatorInfo(a.getCreatorId(), "(알 수 없음)", "")
                            : new CompanyCampaignResponse.CreatorInfo(cr.getId(), cr.getName(), cr.getEmail());
                    List<SubmissionHistoryItem> history = historyByAppId.getOrDefault(a.getId(), List.of());
                    return new CompanyCampaignResponse.ApplicationItem(
                            a.getId(),
                            a.getStatus(),
                            a.getMessage(),
                            a.getSubmissionUrl(),
                            a.getVideoFileKey(),
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

        return new CompanyCampaignResponse(c, apps.size(), applicationItems, escrowItems);
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

        if (c.getStatus() == CampaignStatus.CLOSED) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }

        if (req.getTitle() != null) c.setTitle(req.getTitle());
        if (req.getDescription() != null) c.setDescription(req.getDescription());
        if (req.getBrandName() != null) c.setBrandName(req.getBrandName());
        if (req.getThumbnailUrl() != null) c.setThumbnailUrl(req.getThumbnailUrl());
        if (req.getRequirements() != null) c.setRequirements(req.getRequirements());
        if (req.getDeadline() != null) c.setDeadline(req.getDeadline());

        boolean budgetFieldsIncluded = req.getRewardAmount() != null || req.getMaxParticipants() != null;
        if (budgetFieldsIncluded) {
            if (c.getEscrowStatus() != EscrowStatus.PENDING_DEPOSIT) {
                throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
            }
            if (req.getRewardAmount() != null) {
                if (req.getRewardAmount() < 0) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                c.setRewardAmount(req.getRewardAmount());
            }
            if (req.getMaxParticipants() != null) {
                if (req.getMaxParticipants() < 1) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                c.setMaxParticipants(req.getMaxParticipants());
            }
            c.setTotalBudget(c.getRewardAmount() * c.getMaxParticipants());
        }

        Campaign saved = campaignRepository.save(c);
        return new CompanyCampaignResponse(saved, (int) applicationRepository.countByCampaignId(saved.getId()));
    }

    @Transactional
    public void deleteCampaign(Integer campaignId, Integer companyMemberId) {
        Campaign c = loadOwned(campaignId, companyMemberId);
        if (c.getEscrowStatus() != EscrowStatus.PENDING_DEPOSIT) {
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
        if (applicationRepository.countByCampaignId(campaignId) > 0) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }
        EscrowStatus es = c.getEscrowStatus();
        if (es != EscrowStatus.PENDING_DEPOSIT && es != EscrowStatus.DEPOSIT_CONFIRMING && es != EscrowStatus.FUNDED) {
            throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
        }

        if (es == EscrowStatus.FUNDED) {
            escrowService.refund(campaignId);
        } else {
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
            case SETTLE, APPROVE_VIDEO -> {
                if (app.getStatus() != ApplicationStatus.SUBMITTED) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                int payout = req.getRewardPaidAmount() != null
                        ? req.getRewardPaidAmount()
                        : campaign.getRewardAmount();
                if (payout <= 0 || payout > campaign.getRewardAmount()) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                escrowService.release(campaign.getId(), app.getId(), payout);
                app.setStatus(ApplicationStatus.SETTLED);
                app.setRewardPaidAmount(payout);
                app.setSettledAt(LocalDateTime.now());
                app.setReviewedAt(LocalDateTime.now());
                markLatestSubmission(app.getId(), companyMemberId,
                        SubmissionReviewStatus.APPROVED, null);
                publishApplicationResult(app, campaign, "SETTLED", payout, null);
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
            case REQUEST_REREVIEW -> {
                // [deprecated] REQUEST_CHANGES 로 마이그레이션 이후 사용하지 않음.
                if (app.getStatus() != ApplicationStatus.SUBMITTED) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                app.setStatus(ApplicationStatus.APPROVED);
                app.setSubmissionUrl(null);
                app.setSubmittedAt(null);
            }
        }

        applicationRepository.save(app);
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

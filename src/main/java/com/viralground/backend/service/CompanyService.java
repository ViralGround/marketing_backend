package com.viralground.backend.service;

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
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CampaignRepository campaignRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final MemberRepository memberRepository;
    private final EscrowService escrowService;
    private final EmailService emailService;

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
        return campaignRepository.findByCreatedByIdOrderByCreatedAtDesc(companyMemberId).stream()
                .map(c -> new CompanyCampaignResponse(c,
                        (int) applicationRepository.countByCampaignId(c.getId())))
                .toList();
    }

    public CompanyCampaignResponse getCampaign(Integer campaignId, Integer companyMemberId) {
        Campaign c = loadOwned(campaignId, companyMemberId);
        return new CompanyCampaignResponse(c, (int) applicationRepository.countByCampaignId(c.getId()));
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
            if (req.getRewardAmount() != null) c.setRewardAmount(req.getRewardAmount());
            if (req.getMaxParticipants() != null) c.setMaxParticipants(req.getMaxParticipants());
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
        CampaignApplication app = applicationRepository.findById(applicationId)
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
                notifyCreator(app, campaign, "APPROVED", null);
            }
            case REJECT -> {
                if (app.getStatus() != ApplicationStatus.PENDING) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                app.setStatus(ApplicationStatus.REJECTED);
                app.setReviewedAt(LocalDateTime.now());
                notifyCreator(app, campaign, "REJECTED", null);
            }
            case SETTLE -> {
                if (app.getStatus() != ApplicationStatus.SUBMITTED) {
                    throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
                }
                int payout = req.getRewardPaidAmount() != null
                        ? req.getRewardPaidAmount()
                        : campaign.getRewardAmount();
                escrowService.release(campaign.getId(), app.getId(), payout);
                app.setStatus(ApplicationStatus.SETTLED);
                app.setRewardPaidAmount(payout);
                app.setSettledAt(LocalDateTime.now());
                notifyCreator(app, campaign, "SETTLED", payout);
            }
            case REQUEST_REREVIEW -> {
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

    private void notifyCreator(CampaignApplication app, Campaign campaign, String status, Integer rewardAmount) {
        memberRepository.findById(app.getCreatorId()).ifPresent((Member creator) ->
                emailService.notifyCreatorOfApplicationResult(
                        creator.getEmail(),
                        creator.getName(),
                        campaign.getTitle(),
                        status,
                        rewardAmount
                )
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

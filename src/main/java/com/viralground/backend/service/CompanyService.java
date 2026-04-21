package com.viralground.backend.service;

import com.viralground.backend.dto.company.CompanyCampaignCreateRequest;
import com.viralground.backend.dto.company.CompanyCampaignResponse;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CampaignRepository campaignRepository;
    private final CampaignApplicationRepository applicationRepository;

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

    private Campaign loadOwned(Integer campaignId, Integer companyMemberId) {
        Campaign c = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (!c.getCreatedById().equals(companyMemberId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return c;
    }
}

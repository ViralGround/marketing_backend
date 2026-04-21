package com.viralground.backend.dto.company;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class CompanyCampaignResponse {

    private final Integer id;
    private final String title;
    private final String description;
    private final String brandName;
    private final Integer rewardAmount;
    private final Integer totalBudget;
    private final Integer maxParticipants;
    private final CampaignStatus status;
    private final EscrowStatus escrowStatus;
    private final LocalDateTime deadline;
    private final LocalDateTime depositRequestedAt;
    private final LocalDateTime fundedAt;
    private final LocalDateTime createdAt;
    private final Integer applicationCount;

    public CompanyCampaignResponse(Campaign c, Integer applicationCount) {
        this.id = c.getId();
        this.title = c.getTitle();
        this.description = c.getDescription();
        this.brandName = c.getBrandName();
        this.rewardAmount = c.getRewardAmount();
        this.totalBudget = c.getTotalBudget();
        this.maxParticipants = c.getMaxParticipants();
        this.status = c.getStatus();
        this.escrowStatus = c.getEscrowStatus();
        this.deadline = c.getDeadline();
        this.depositRequestedAt = c.getDepositRequestedAt();
        this.fundedAt = c.getFundedAt();
        this.createdAt = c.getCreatedAt();
        this.applicationCount = applicationCount;
    }
}

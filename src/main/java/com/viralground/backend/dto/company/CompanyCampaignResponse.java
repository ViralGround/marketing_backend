package com.viralground.backend.dto.company;

import com.viralground.backend.dto.campaign.SubmissionHistoryItem;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.entity.EscrowTxType;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

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
    private final String requirements;
    private final String thumbnailUrl;
    private final List<ApplicationItem> applications;
    private final List<EscrowTransactionItem> escrowTransactions;

    public CompanyCampaignResponse(Campaign c, Integer applicationCount, String thumbnailUrl) {
        this(c, applicationCount, List.of(), List.of(), thumbnailUrl);
    }

    public CompanyCampaignResponse(Campaign c, Integer applicationCount,
                                   List<ApplicationItem> applications,
                                   List<EscrowTransactionItem> escrowTransactions,
                                   String thumbnailUrl) {
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
        this.requirements = c.getRequirements();
        this.thumbnailUrl = thumbnailUrl;
        this.applications = applications;
        this.escrowTransactions = escrowTransactions;
    }

    public record ApplicationItem(
            Integer id,
            ApplicationStatus status,
            String message,
            String submissionUrl,
            String videoFileKey,
            Integer resubmissionCount,
            String reviewComment,
            Integer rewardPaidAmount,
            LocalDateTime appliedAt,
            LocalDateTime submittedAt,
            LocalDateTime settledAt,
            CreatorInfo creator,
            List<SubmissionHistoryItem> submissions
    ) {}

    public record CreatorInfo(Integer id, String name, String email) {}

    public record EscrowTransactionItem(
            Integer id,
            EscrowTxType type,
            Integer amount,
            String memo,
            LocalDateTime createdAt
    ) {}
}

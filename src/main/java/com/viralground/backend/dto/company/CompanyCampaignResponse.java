package com.viralground.backend.dto.company;

import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.entity.EscrowTxType;
import com.viralground.backend.entity.SubmissionReviewStatus;
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
            String videoUrl,
            Integer resubmissionCount,
            String reviewComment,
            Integer rewardPaidAmount,
            LocalDateTime appliedAt,
            LocalDateTime submittedAt,
            LocalDateTime settledAt,
            CreatorInfo creator,
            List<CompanySubmissionItem> submissions
    ) {}

    /** 제3자 제공 동의 범위 밖의 이메일은 기업 응답에 포함하지 않는다. */
    public record CreatorInfo(Integer id, String name) {}

    /** 내부 object key 대신 기업이 짧게 사용할 수 있는 서명 URL만 제공한다. */
    public record CompanySubmissionItem(
            Integer id,
            String videoUrl,
            String videoContentType,
            Long videoSizeBytes,
            String submissionUrl,
            SubmissionReviewStatus status,
            String reviewComment,
            LocalDateTime submittedAt,
            LocalDateTime reviewedAt
    ) {}

    public record EscrowTransactionItem(
            Integer id,
            EscrowTxType type,
            Integer amount,
            String memo,
            LocalDateTime createdAt
    ) {}
}

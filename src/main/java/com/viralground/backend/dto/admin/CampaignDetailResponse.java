package com.viralground.backend.dto.admin;

import com.viralground.backend.dto.campaign.SubmissionHistoryItem;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.Member;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
public class CampaignDetailResponse {

    private final Integer id;
    private final String title;
    private final String description;
    private final String brandName;
    private final Integer rewardAmount;
    private final String thumbnailUrl;
    private final String requirements;
    private final LocalDateTime deadline;
    private final Integer maxParticipants;
    private final String status;
    private final String escrowStatus;
    private final LocalDateTime fundedAt;
    private final LocalDateTime createdAt;
    private final boolean hidden;
    private final LocalDateTime hiddenAt;
    private final List<ApplicationInfo> applications;

    public CampaignDetailResponse(Campaign c, List<CampaignApplication> apps, String thumbnailUrl) {
        this(c, apps, Map.of(), thumbnailUrl);
    }

    public CampaignDetailResponse(Campaign c, List<CampaignApplication> apps,
                                  Map<Integer, List<SubmissionHistoryItem>> submissionsByAppId,
                                  String thumbnailUrl) {
        this.id = c.getId();
        this.title = c.getTitle();
        this.description = c.getDescription();
        this.brandName = c.getBrandName();
        this.rewardAmount = c.getRewardAmount();
        this.thumbnailUrl = thumbnailUrl;
        this.requirements = c.getRequirements();
        this.deadline = c.getDeadline();
        this.maxParticipants = c.getMaxParticipants();
        this.status = c.getStatus().name();
        this.escrowStatus = c.getEscrowStatus().name();
        this.fundedAt = c.getFundedAt();
        this.createdAt = c.getCreatedAt();
        this.hidden = c.isHidden();
        this.hiddenAt = c.getHiddenAt();
        this.applications = apps.stream()
                .map(a -> new ApplicationInfo(a,
                        submissionsByAppId.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    public record ApplicationInfo(
            Integer id, String status, String message,
            String submissionUrl, String videoFileKey,
            Integer resubmissionCount, String reviewComment,
            Integer rewardPaidAmount,
            LocalDateTime appliedAt, CreatorInfo creator,
            List<SubmissionHistoryItem> submissions) {
        ApplicationInfo(CampaignApplication a, List<SubmissionHistoryItem> submissions) {
            this(a.getId(), a.getStatus().name(), a.getMessage(),
                    a.getSubmissionUrl(), a.getVideoFileKey(),
                    a.getResubmissionCount(), a.getReviewComment(),
                    a.getRewardPaidAmount(), a.getAppliedAt(),
                    a.getCreator() != null ? new CreatorInfo(a.getCreator()) : null,
                    submissions);
        }
    }

    public record CreatorInfo(Integer id, String name, String email) {
        CreatorInfo(Member m) { this(m.getId(), m.getName(), m.getEmail()); }
    }
}

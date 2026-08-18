package com.viralground.backend.dto.admin;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.SubmissionReviewStatus;
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
    private final String brandIntroduction;
    private final String brandLogoUrl;
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
        this(c, apps, Map.of(), Map.of(), thumbnailUrl, null);
    }

    public CampaignDetailResponse(Campaign c, List<CampaignApplication> apps,
                                  Map<Integer, String> currentVideoUrlByAppId,
                                  Map<Integer, List<AdminSubmissionItem>> submissionsByAppId,
                                  String thumbnailUrl, String brandLogoUrl) {
        this.id = c.getId();
        this.title = c.getTitle();
        this.description = c.getDescription();
        this.brandName = c.getBrandName();
        this.brandIntroduction = c.getBrandIntroduction();
        this.brandLogoUrl = brandLogoUrl;
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
                        currentVideoUrlByAppId.get(a.getId()),
                        submissionsByAppId.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    public record ApplicationInfo(
            Integer id, String status, String message,
            String submissionUrl, String videoUrl,
            Integer resubmissionCount, String reviewComment,
            Integer rewardPaidAmount,
            LocalDateTime appliedAt, CreatorInfo creator,
            List<AdminSubmissionItem> submissions) {
        ApplicationInfo(CampaignApplication a, String videoUrl,
                        List<AdminSubmissionItem> submissions) {
            this(a.getId(), a.getStatus().name(), a.getMessage(),
                    a.getSubmissionUrl(), videoUrl,
                    a.getResubmissionCount(), a.getReviewComment(),
                    a.getRewardPaidAmount(), a.getAppliedAt(),
                    a.getCreator() != null ? new CreatorInfo(a.getCreator()) : null,
                    submissions);
        }
    }

    public record AdminSubmissionItem(
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

    public record CreatorInfo(Integer id, String name, String email) {
        CreatorInfo(Member m) { this(m.getId(), m.getName(), m.getEmail()); }
    }
}

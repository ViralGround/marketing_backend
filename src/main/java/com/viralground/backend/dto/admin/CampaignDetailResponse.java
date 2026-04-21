package com.viralground.backend.dto.admin;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.Member;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

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
    private final LocalDateTime createdAt;
    private final List<ApplicationInfo> applications;

    public CampaignDetailResponse(Campaign c, List<CampaignApplication> apps) {
        this.id = c.getId();
        this.title = c.getTitle();
        this.description = c.getDescription();
        this.brandName = c.getBrandName();
        this.rewardAmount = c.getRewardAmount();
        this.thumbnailUrl = c.getThumbnailUrl();
        this.requirements = c.getRequirements();
        this.deadline = c.getDeadline();
        this.maxParticipants = c.getMaxParticipants();
        this.status = c.getStatus().name();
        this.createdAt = c.getCreatedAt();
        this.applications = apps.stream().map(ApplicationInfo::new).toList();
    }

    public record ApplicationInfo(
            Integer id, String status, String message,
            String submissionUrl, Integer rewardPaidAmount,
            LocalDateTime appliedAt, CreatorInfo creator) {
        ApplicationInfo(CampaignApplication a) {
            this(a.getId(), a.getStatus().name(), a.getMessage(),
                    a.getSubmissionUrl(), a.getRewardPaidAmount(), a.getAppliedAt(),
                    a.getCreator() != null ? new CreatorInfo(a.getCreator()) : null);
        }
    }

    public record CreatorInfo(Integer id, String name, String email) {
        CreatorInfo(Member m) { this(m.getId(), m.getName(), m.getEmail()); }
    }
}

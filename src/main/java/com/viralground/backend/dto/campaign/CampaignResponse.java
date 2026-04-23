package com.viralground.backend.dto.campaign;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.CampaignStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class CampaignResponse {

    private final Integer id;
    private final String title;
    private final String description;
    private final String brandName;
    private final Integer rewardAmount;
    private final String thumbnailUrl;
    private final String requirements;
    private final LocalDateTime deadline;
    private final Integer maxParticipants;
    private final CampaignStatus status;
    private final LocalDateTime createdAt;
    private final Integer applicationCount;
    private final ApplicationSummary myApplication;

    public CampaignResponse(Campaign c, CampaignApplication myApp, int applicationCount) {
        this.id = c.getId();
        this.title = c.getTitle();
        this.description = c.getDescription();
        this.brandName = c.getBrandName();
        this.rewardAmount = c.getRewardAmount();
        this.thumbnailUrl = c.getThumbnailUrl();
        this.requirements = c.getRequirements();
        this.deadline = c.getDeadline();
        this.maxParticipants = c.getMaxParticipants();
        this.status = c.getStatus();
        this.createdAt = c.getCreatedAt();
        this.applicationCount = applicationCount;
        this.myApplication = myApp != null ? new ApplicationSummary(myApp) : null;
    }

    public record ApplicationSummary(Integer id, String status) {
        ApplicationSummary(CampaignApplication a) {
            this(a.getId(), a.getStatus().name());
        }
    }
}

package com.viralground.backend.dto.admin;

import com.viralground.backend.entity.CampaignStatus;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateCampaignAdminRequest {

    private String title;
    private String description;
    private String brandName;

    @Min(0)
    private Integer rewardAmount;

    @Min(1)
    private Integer maxParticipants;

    private String thumbnailFileKey;
    private String requirements;
    private CampaignStatus status;
}

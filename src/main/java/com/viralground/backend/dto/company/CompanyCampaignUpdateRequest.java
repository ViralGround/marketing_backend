package com.viralground.backend.dto.company;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class CompanyCampaignUpdateRequest {

    private String title;
    private String description;
    private String brandName;
    private Integer rewardAmount;
    private Integer maxParticipants;
    private String thumbnailUrl;
    private String requirements;
    private LocalDateTime deadline;
}

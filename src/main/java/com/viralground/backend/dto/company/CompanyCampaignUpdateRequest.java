package com.viralground.backend.dto.company;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class CompanyCampaignUpdateRequest {

    private String title;
    private String description;
    private String brandName;
    @Min(1)
    @Max(100000000)
    private Integer rewardAmount;

    @Min(1)
    @Max(10000)
    private Integer maxParticipants;
    private String thumbnailFileKey;
    private String requirements;
    private LocalDateTime deadline;
}

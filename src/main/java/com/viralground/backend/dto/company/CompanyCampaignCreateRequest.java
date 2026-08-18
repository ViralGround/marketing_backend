package com.viralground.backend.dto.company;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class CompanyCampaignCreateRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String description;

    @NotBlank
    private String brandName;

    @NotNull
    @Min(1)
    @Max(100000000)
    private Integer rewardAmount;

    @NotNull
    @Min(1)
    @Max(10000)
    private Integer maxParticipants;

    private String thumbnailFileKey;
    private String requirements;
    private LocalDateTime deadline;
}

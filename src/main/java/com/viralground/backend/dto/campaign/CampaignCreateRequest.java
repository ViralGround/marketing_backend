package com.viralground.backend.dto.campaign;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class CampaignCreateRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String description;

    @NotBlank
    private String brandName;

    @NotNull
    @Min(0)
    private Integer rewardAmount;

    @NotNull
    @Min(1)
    private Integer maxParticipants;

    private String thumbnailFileKey;
    private String requirements;
    private LocalDateTime deadline;

    /**
     * true (또는 null) 이면 OPEN/FUNDED 로 즉시 생성, false 면 DRAFT/PENDING_DEPOSIT 로 생성.
     */
    private Boolean immediatelyOpen;
}

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

    /** 브랜드 소개글(랜딩 회사 소개 모달 노출). 관리자 직접 생성 캠페인용. */
    private String brandIntroduction;

    /** 브랜드 로고 스토리지 키. 관리자 직접 생성 캠페인용. */
    private String brandLogoFileKey;

    /**
     * true (또는 null) 이면 OPEN/FUNDED 로 즉시 생성, false 면 DRAFT/PENDING_DEPOSIT 로 생성.
     */
    private Boolean immediatelyOpen;
}

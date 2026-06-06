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

    /** 브랜드 소개글. 전달 시 갱신. */
    private String brandIntroduction;

    /** 브랜드 로고 스토리지 키. 전달 시 갱신, 빈 문자열은 로고 제거로 본다. */
    private String brandLogoFileKey;
}

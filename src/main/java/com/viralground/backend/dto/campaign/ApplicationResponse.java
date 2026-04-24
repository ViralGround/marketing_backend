package com.viralground.backend.dto.campaign;

import com.viralground.backend.entity.CampaignApplication;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ApplicationResponse {

    private final Integer id;
    private final Integer campaignId;
    private final String status;
    private final String message;
    private final String submissionUrl;
    private final String videoFileKey;
    private final Integer rewardPaidAmount;
    private final LocalDateTime appliedAt;
    private final CampaignInfo campaign;

    public ApplicationResponse(CampaignApplication a) {
        this.id = a.getId();
        this.campaignId = a.getCampaignId();
        this.status = a.getStatus().name();
        this.message = a.getMessage();
        this.submissionUrl = a.getSubmissionUrl();
        this.videoFileKey = a.getVideoFileKey();
        this.rewardPaidAmount = a.getRewardPaidAmount();
        this.appliedAt = a.getAppliedAt();
        this.campaign = a.getCampaign() != null ? new CampaignInfo(a) : null;
    }

    public record CampaignInfo(Integer id, String title, String brandName, Integer rewardAmount) {
        CampaignInfo(CampaignApplication a) {
            this(a.getCampaignId(), a.getCampaign().getTitle(),
                    a.getCampaign().getBrandName(), a.getCampaign().getRewardAmount());
        }
    }
}

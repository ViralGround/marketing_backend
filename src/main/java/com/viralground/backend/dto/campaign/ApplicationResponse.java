package com.viralground.backend.dto.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.viralground.backend.entity.CampaignApplication;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class ApplicationResponse {

    private final Integer id;
    private final Integer campaignId;
    private final String status;
    private final String message;
    private final String submissionUrl;
    private final String videoFileKey;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Integer rewardPaidAmount;
    private final Integer resubmissionCount;
    private final String reviewComment;
    private final LocalDateTime appliedAt;
    private final List<SubmissionHistoryItem> submissions;
    private final CampaignInfo campaign;

    public ApplicationResponse(CampaignApplication a) {
        this(a, List.of(), false);
    }

    public ApplicationResponse(CampaignApplication a, List<SubmissionHistoryItem> submissions) {
        this(a, submissions, false);
    }

    public ApplicationResponse(CampaignApplication a, boolean exposePayments) {
        this(a, List.of(), exposePayments);
    }

    public ApplicationResponse(CampaignApplication a, List<SubmissionHistoryItem> submissions,
                               boolean exposePayments) {
        this.id = a.getId();
        this.campaignId = a.getCampaignId();
        this.status = a.getApiStatus();
        this.message = a.getMessage();
        this.submissionUrl = a.getSubmissionUrl();
        this.videoFileKey = a.getVideoFileKey();
        this.rewardPaidAmount = exposePayments ? a.getRewardPaidAmount() : null;
        this.resubmissionCount = a.getResubmissionCount();
        this.reviewComment = a.getReviewComment();
        this.appliedAt = a.getAppliedAt();
        this.submissions = submissions;
        this.campaign = a.getCampaign() != null ? new CampaignInfo(a, exposePayments) : null;
    }

    public record CampaignInfo(Integer id, String title, String brandName,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer rewardAmount) {
        CampaignInfo(CampaignApplication a, boolean exposePayments) {
            this(a.getCampaignId(), a.getCampaign().getTitle(),
                    a.getCampaign().getBrandName(), exposePayments ? a.getCampaign().getRewardAmount() : null);
        }
    }
}

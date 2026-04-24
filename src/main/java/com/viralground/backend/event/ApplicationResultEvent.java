package com.viralground.backend.event;

public record ApplicationResultEvent(
        String creatorEmail,
        String creatorName,
        String campaignTitle,
        String status,
        Integer rewardAmount,
        String reviewComment
) {
    public ApplicationResultEvent(String creatorEmail, String creatorName,
                                  String campaignTitle, String status, Integer rewardAmount) {
        this(creatorEmail, creatorName, campaignTitle, status, rewardAmount, null);
    }
}

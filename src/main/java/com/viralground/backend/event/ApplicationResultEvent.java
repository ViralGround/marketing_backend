package com.viralground.backend.event;

public record ApplicationResultEvent(
        String creatorEmail,
        String creatorName,
        String campaignTitle,
        String status,
        Integer rewardAmount
) {}

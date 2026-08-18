package com.viralground.backend.validation;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;

/** 캠페인 예산의 서비스/DB 공통 한계. 금액 단위는 원(KRW)이다. */
public final class CampaignBudgetPolicy {
    public static final int MAX_REWARD_AMOUNT = 100_000_000;
    public static final int MAX_PARTICIPANTS = 10_000;

    private CampaignBudgetPolicy() {
    }

    public static int totalBudget(Integer rewardAmount, Integer maxParticipants) {
        if (rewardAmount == null || maxParticipants == null
                || rewardAmount <= 0 || rewardAmount > MAX_REWARD_AMOUNT
                || maxParticipants <= 0 || maxParticipants > MAX_PARTICIPANTS) {
            throw invalid();
        }
        try {
            long total = Math.multiplyExact((long) rewardAmount, (long) maxParticipants);
            if (total > Integer.MAX_VALUE) throw invalid();
            return Math.toIntExact(total);
        } catch (ArithmeticException overflow) {
            throw invalid();
        }
    }

    private static AppException invalid() {
        return new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
    }
}

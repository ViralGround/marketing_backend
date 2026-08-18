package com.viralground.backend.validation;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignBudgetPolicyTest {

    @Test
    void calculatesValidBudgetExactly() {
        assertThat(CampaignBudgetPolicy.totalBudget(250_000, 40)).isEqualTo(10_000_000);
    }

    @Test
    void rejectsNonPositiveOutOfRangeAndIntegerOverflow() {
        assertInvalid(0, 1);
        assertInvalid(1, 0);
        assertInvalid(CampaignBudgetPolicy.MAX_REWARD_AMOUNT + 1, 1);
        assertInvalid(1, CampaignBudgetPolicy.MAX_PARTICIPANTS + 1);
        assertInvalid(CampaignBudgetPolicy.MAX_REWARD_AMOUNT,
                CampaignBudgetPolicy.MAX_PARTICIPANTS);
    }

    private void assertInvalid(Integer rewardAmount, Integer maxParticipants) {
        assertThatThrownBy(() -> CampaignBudgetPolicy.totalBudget(
                rewardAmount, maxParticipants))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
    }
}

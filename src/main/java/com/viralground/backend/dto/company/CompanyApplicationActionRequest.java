package com.viralground.backend.dto.company;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompanyApplicationActionRequest {

    public enum Action {
        APPROVE,
        REJECT,
        SETTLE,
        REQUEST_REREVIEW
    }

    @NotNull
    private Action action;

    @Positive
    private Integer rewardPaidAmount;
}

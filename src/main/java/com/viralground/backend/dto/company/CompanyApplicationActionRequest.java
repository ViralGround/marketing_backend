package com.viralground.backend.dto.company;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompanyApplicationActionRequest {

    public enum Action {
        APPROVE,             // 지원 승인 (PENDING → APPROVED)
        REJECT,              // 지원 거절 (PENDING → REJECTED)
        SETTLE,              // [deprecated] APPROVE_VIDEO 로 대체. 남겨둠
        REQUEST_REREVIEW,    // [deprecated] REQUEST_CHANGES 로 대체. 남겨둠
        APPROVE_VIDEO,       // 영상 승인 + 정산 (SUBMITTED → SETTLED, 에스크로 release)
        REQUEST_CHANGES,     // 수정 요청 (SUBMITTED → CHANGES_REQUESTED, reviewComment 필수)
        REJECT_VIDEO         // 영상 최종 거절 (SUBMITTED → REJECTED)
    }

    @NotNull
    private Action action;

    @Positive
    private Integer rewardPaidAmount;

    /** REQUEST_CHANGES 에서 필수, REJECT_VIDEO 에서 선택. */
    private String reviewComment;
}

package com.viralground.backend.dto.admin;

import com.viralground.backend.entity.ApplicationStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateApplicationStatusRequest {

    @NotNull(message = "상태 값이 필요합니다")
    private ApplicationStatus status;

    @Min(0)
    private Integer rewardPaidAmount;

    /** CHANGES_REQUESTED 일 때 필수, REJECTED 일 때 선택. */
    private String reviewComment;

    /** SETTLED 전이에 남길 관리자 감사 사유. 미입력 시 안전한 기본 사유를 기록한다. */
    @Size(max = 500)
    private String operationReason;

    /** SETTLED 재시도에서 동일하게 보내야 하는 클라이언트 요청 키. */
    @Size(max = 160)
    private String idempotencyKey;
}

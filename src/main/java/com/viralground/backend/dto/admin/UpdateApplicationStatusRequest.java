package com.viralground.backend.dto.admin;

import com.viralground.backend.entity.ApplicationStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateApplicationStatusRequest {

    @NotNull(message = "상태 값이 필요합니다")
    private ApplicationStatus status;

    @Min(0)
    private Integer rewardPaidAmount;
}

package com.viralground.backend.dto.admin;

import com.viralground.backend.entity.MemberStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateMemberStatusRequest {

    @NotNull(message = "상태 값이 필요합니다")
    private MemberStatus status;
}

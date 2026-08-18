package com.viralground.backend.dto.company;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 기업 프로필 수정 요청. 모든 필드는 선택값으로, 전달된 필드만 갱신한다.
 * logoFileKey 빈 문자열은 로고 제거로 해석한다.
 */
@Getter
@Setter
public class UpdateCompanyProfileRequest {

    private String introduction;
    private String logoFileKey;
    private String industry;
    @Size(max = 500)
    private String homepage;
}

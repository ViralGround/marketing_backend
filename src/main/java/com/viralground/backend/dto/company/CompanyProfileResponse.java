package com.viralground.backend.dto.company;

/**
 * 기업 본인이 자기 프로필 수정 화면에서 보는 응답. companyName 은 가입 시 확정된 값이라
 * 읽기전용으로 표시하고, industry·homepage·introduction·logo 는 수정 가능 필드다.
 */
public record CompanyProfileResponse(
        String companyName,
        String industry,
        String homepage,
        String introduction,
        String logoUrl
) {
}

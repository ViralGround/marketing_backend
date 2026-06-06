package com.viralground.backend.dto.landing;

import java.time.LocalDateTime;

/**
 * 랜딩 페이지 대표 캠페인 카드용 응답. 비로그인 노출 대상이므로 카드 렌더에 필요한
 * 최소 필드만 담는다. description·requirements 등 상세/민감 필드는 의도적으로 제외.
 * companyMemberId 는 회사 소개 모달 조회 키이며, 작성자 프로필이 없으면 호출부에서 null.
 */
public record FeaturedCampaignResponse(
        Integer id,
        String title,
        String brandName,
        Integer rewardAmount,
        LocalDateTime deadline,
        Integer maxParticipants,
        Integer applicationCount,
        String thumbnailUrl,
        Integer companyMemberId,
        /** 로고 서명 URL: 회원 기업 로고 우선, 없으면 캠페인 브랜드 로고. 없으면 null(이니셜 폴백). */
        String logoUrl,
        /** 브랜드 소개글. 관리자 직접 생성 캠페인의 모달 인라인 표시용. 회원 회사 캠페인은 모달이 별도 조회. */
        String brandIntroduction
) {
}

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
        /** 작성 기업의 로고 서명 URL. 없으면 null(카드는 brandName 이니셜 아바타로 폴백). */
        String logoUrl
) {
}

package com.viralground.backend.dto.landing;

import java.time.LocalDateTime;

/**
 * 공개 크리에이터 풀 카드용 응답. 비로그인 노출 대상이므로 이름·집계 성과만 담고
 * 연락처·이메일 등 개인 식별 정보는 의도적으로 제외한다.
 * 완료(SETTLED) 캠페인이 1건 이상인 크리에이터만 목록에 오른다.
 */
public record CreatorPublicResponse(
        Integer id,
        String name,
        LocalDateTime joinedAt,
        int completedCampaigns,
        int reviewCount,
        /** 소수 1자리 반올림. 리뷰가 없으면 0.0 — 프론트에서 "-" 처리. */
        double averageRating,
        long totalViews,
        /** metric 이 입력된 완료 건 기준 평균 조회수. 표본이 없으면 0. */
        long averageViews
) {
}

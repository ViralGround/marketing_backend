package com.viralground.backend.instagram;

/**
 * 로컬 개발에서만 사용하는 결정적 릴스 지표 공급 포트.
 * 운영 지표는 {@link InstagramConnectionProvider}의 Meta 구현을 통해 직접 가져온다.
 */
public interface InstagramMetricsProvider {

    /** 릴스 URL 의 현재 지표(조회/좋아요/댓글 + 최근 일별 조회수)를 가져온다. */
    ReelMetrics fetch(String reelUrl);
}

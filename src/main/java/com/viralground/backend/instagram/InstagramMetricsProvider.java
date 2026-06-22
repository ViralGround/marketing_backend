package com.viralground.backend.instagram;

/**
 * 릴스 지표 공급자. 현재는 {@link MockInstagramMetricsProvider}(가짜 지표),
 * 실제 인스타그램 Graph API 연동 시 이 인터페이스의 구현만 교체한다.
 * (결제의 PaymentGateway / MockPaymentGateway 와 동일한 교체 패턴.)
 */
public interface InstagramMetricsProvider {

    /** 릴스 URL 의 현재 지표(조회/좋아요/댓글 + 최근 일별 조회수)를 가져온다. */
    ReelMetrics fetch(String reelUrl);
}

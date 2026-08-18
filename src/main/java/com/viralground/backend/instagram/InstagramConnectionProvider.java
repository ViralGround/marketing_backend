package com.viralground.backend.instagram;

import com.viralground.backend.entity.CreatorInstagramConnection;

import java.time.Instant;

/**
 * 크리에이터 인스타그램 연동 포트. 운영 구현은 Meta Instagram Graph API를 직접 사용하며,
 * 명시적인 로컬 개발 설정에서만 mock 구현을 사용할 수 있다.
 *
 * <p>{@link InstagramMetricsProvider}(대시보드 데모 집계용)와 달리, 이 포트는
 * <b>연결된 크리에이터</b>의 동의를 기반으로 지표를 수집한다.
 */
public interface InstagramConnectionProvider {

    /** OAuth state를 포함한 Meta 동의 화면 URL을 만든다. */
    String buildAuthorizationUrl(String state, String profileHandle);

    /** 서버가 authorization code를 교환하고, 소유 계정과 장기 토큰을 검증해 반환한다. */
    AuthorizationResult exchangeAuthorizationCode(String code);

    /** 계정 소유권 불일치 등 저장 전 실패 시 방금 발급된 권한을 철회한다. */
    void revoke(AuthorizationResult authorization);

    /** 연결된 계정의 릴스 URL 지표(shares 포함)를 가져온다. */
    ReelMetrics fetchReelMetrics(CreatorInstagramConnection connection, String reelUrl);

    /** Meta 권한을 철회한다. 성공하지 못하면 예외를 던져 로컬 연결도 유지한다. */
    void revoke(CreatorInstagramConnection connection);

    /** 서버가 검증한 Meta 계정과 장기 토큰. accessToken은 암호화 직전 메모리에서만 사용한다. */
    record AuthorizationResult(
            String accountId,
            String username,
            String accessToken,
            Instant expiresAt) {
    }
}

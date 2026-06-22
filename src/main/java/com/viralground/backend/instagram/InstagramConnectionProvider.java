package com.viralground.backend.instagram;

import com.viralground.backend.entity.CreatorInstagramConnection;

import java.util.Optional;

/**
 * 크리에이터 인스타그램 연동 포트. 애그리게이터(Phyllo)별 구현을 뒤에 두고
 * {@code instagram.provider} 설정으로 교체한다(mock/phyllo).
 *
 * <p>{@link InstagramMetricsProvider}(대시보드 데모 집계용)와 달리, 이 포트는
 * <b>연결된 크리에이터</b>의 동의를 기반으로 지표를 수집한다.
 */
public interface InstagramConnectionProvider {

    /**
     * 크리에이터 연결용 SDK 토큰을 발급한다. 프론트가 token+userId+environment 로 Connect SDK 를 연다.
     *
     * @param creatorId                크리에이터 id
     * @param creatorName              크리에이터 표시명(애그리게이터 user 생성 시 사용)
     * @param existingProviderUserId   기존에 발급된 애그리게이터 user id(없으면 null) — 있으면 재사용
     */
    ConnectToken createConnectToken(int creatorId, String creatorName, String existingProviderUserId);

    /** 연결된 계정의 릴스 URL 지표(shares 포함)를 가져온다. */
    ReelMetrics fetchReelMetrics(CreatorInstagramConnection conn, String reelUrl);

    /** 연결된 계정의 인스타 username(핸들)을 조회한다. 프로필 일치 검증·표시용. 조회 불가 시 null. */
    String fetchAccountUsername(String providerAccountId);

    /**
     * 애그리게이터에서 해당 user 의 <b>연결된</b> 계정 id 를 조회한다(콜백 누락 자동 복구용).
     * 연결된 계정이 없으면 empty. mock 은 항상 empty(복구 불필요).
     */
    Optional<String> findConnectedAccountId(String providerUserId);

    /**
     * Connect SDK 초기화에 필요한 값.
     *
     * @param token       SDK 토큰(단기). mock 에서는 mock 토큰.
     * @param userId      애그리게이터 user id(Connect SDK initialize 에 전달, 연결 레코드에 저장).
     * @param environment SDK 환경(sandbox/staging/production), mock provider 는 "mock".
     */
    record ConnectToken(String token, String userId, String environment) {}
}

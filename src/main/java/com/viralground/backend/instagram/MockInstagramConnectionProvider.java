package com.viralground.backend.instagram;

import com.viralground.backend.entity.CreatorInstagramConnection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 인스타그램 연동 포트의 목 구현(기본). 키 발급 전·테스트·데모에서 사용한다.
 * 연결 토큰은 {@code "mock-"+creatorId}, 지표는 {@link MockInstagramMetricsProvider}(결정적 시드)에 위임.
 *
 * <p>{@code instagram.provider=phyllo} 로 두면 {@link com.viralground.backend.instagram.phyllo.PhylloInstagramConnectionProvider}
 * 로 교체된다. 설정이 없으면 이 빈이 기본(matchIfMissing).
 */
@Component
@ConditionalOnProperty(name = "instagram.provider", havingValue = "mock", matchIfMissing = true)
public class MockInstagramConnectionProvider implements InstagramConnectionProvider {

    private final InstagramMetricsProvider metricsProvider;

    public MockInstagramConnectionProvider(InstagramMetricsProvider metricsProvider) {
        this.metricsProvider = metricsProvider;
    }

    @Override
    public ConnectToken createConnectToken(int creatorId, String creatorName, String existingProviderUserId) {
        return new ConnectToken("mock-token-" + creatorId, "mock-user-" + creatorId, "mock");
    }

    @Override
    public ReelMetrics fetchReelMetrics(CreatorInstagramConnection conn, String reelUrl) {
        return metricsProvider.fetch(reelUrl);
    }

    @Override
    public String fetchAccountUsername(String providerAccountId) {
        return null; // mock 은 실제 계정이 없어 일치 검증/표시를 생략한다
    }

    @Override
    public Optional<String> findConnectedAccountId(String providerUserId) {
        return Optional.empty(); // mock 은 자동 복구 불필요
    }
}

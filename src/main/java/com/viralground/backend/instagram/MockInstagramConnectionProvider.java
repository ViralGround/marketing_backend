package com.viralground.backend.instagram;

import com.viralground.backend.entity.CreatorInstagramConnection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * 명시적인 로컬 개발용 목 구현. 운영 환경에서는
 * {@link InstagramProviderSafetyValidator}가 mock 기동을 차단한다.
 */
@Component
@ConditionalOnProperty(name = "instagram.provider", havingValue = "mock")
public class MockInstagramConnectionProvider implements InstagramConnectionProvider {

    private final InstagramMetricsProvider metricsProvider;
    private final String redirectUri;
    private final Clock clock;

    public MockInstagramConnectionProvider(
            InstagramMetricsProvider metricsProvider,
            @Value("${meta.instagram.redirect-uri:http://localhost:8080/instagram/meta/oauth/callback}")
            String redirectUri,
            Clock clock) {
        this.metricsProvider = metricsProvider;
        this.redirectUri = redirectUri;
        this.clock = clock;
    }

    @Override
    public String buildAuthorizationUrl(String state, String profileHandle) {
        return redirectUri + "?state="
                + URLEncoder.encode(state, StandardCharsets.UTF_8)
                + "&code=" + URLEncoder.encode("mock:" + profileHandle, StandardCharsets.UTF_8);
    }

    @Override
    public AuthorizationResult exchangeAuthorizationCode(String code) {
        if (code == null || !code.startsWith("mock:")) {
            throw new IllegalArgumentException("유효하지 않은 mock authorization code입니다");
        }
        String username = code.substring("mock:".length());
        return new AuthorizationResult("mock-account-" + username, username,
                "mock-access-token", clock.instant().plusSeconds(3600));
    }

    @Override
    public ReelMetrics fetchReelMetrics(CreatorInstagramConnection conn, String reelUrl) {
        return metricsProvider.fetch(reelUrl);
    }

    @Override
    public void revoke(AuthorizationResult authorization) {
        // 로컬 개발용 mock에는 외부 권한이 없다.
    }

    @Override
    public void revoke(CreatorInstagramConnection connection) {
        // 로컬 개발용 mock에는 외부 권한이 없다.
    }
}

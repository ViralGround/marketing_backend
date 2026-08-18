package com.viralground.backend.instagram;

import com.viralground.backend.entity.CreatorInstagramConnection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockInstagramConnectionProviderTest {

    private final MockInstagramMetricsProvider metrics = new MockInstagramMetricsProvider();
    private final MockInstagramConnectionProvider provider = new MockInstagramConnectionProvider(
            metrics, "http://localhost:8080/instagram/meta/oauth/callback", java.time.Clock.systemUTC());

    @Test
    void mockAuthorizationRedirectsThroughSameBackendCallbackContract() {
        String url = provider.buildAuthorizationUrl("state-value", "creator.handle");
        assertThat(url).startsWith("http://localhost:8080/instagram/meta/oauth/callback?")
                .contains("state=state-value")
                .contains("code=mock%3Acreator.handle");
    }

    @Test
    void mockCodeReturnsDeterministicDevelopmentIdentity() {
        InstagramConnectionProvider.AuthorizationResult result =
                provider.exchangeAuthorizationCode("mock:creator.handle");
        assertThat(result.accountId()).isEqualTo("mock-account-creator.handle");
        assertThat(result.username()).isEqualTo("creator.handle");
        assertThat(result.accessToken()).isEqualTo("mock-access-token");
    }

    @Test
    void metricsDelegateToDeterministicMock() {
        String url = "https://www.instagram.com/reel/abc123/";
        CreatorInstagramConnection connection = CreatorInstagramConnection.builder().creatorId(1).build();
        assertThat(provider.fetchReelMetrics(connection, url)).isEqualTo(metrics.fetch(url));
    }
}

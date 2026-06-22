package com.viralground.backend.instagram;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.instagram.InstagramConnectionProvider.ConnectToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockInstagramConnectionProviderTest {

    private final MockInstagramConnectionProvider provider =
            new MockInstagramConnectionProvider(new MockInstagramMetricsProvider());

    @Test
    void 연결_토큰은_creatorId_기반의_빈값이_아닌_토큰과_userId_환경을_반환한다() {
        // when
        ConnectToken token = provider.createConnectToken(42, "테스터", null);

        // then
        assertThat(token.token()).isNotBlank();
        assertThat(token.token()).contains("42");
        assertThat(token.userId()).contains("42");
        assertThat(token.environment()).isEqualTo("mock");
    }

    @Test
    void 릴스_지표는_URL_기반_결정적_값을_shares_포함해_반환한다() {
        // given
        CreatorInstagramConnection conn = CreatorInstagramConnection.builder()
                .creatorId(1).status(ConnectionStatus.CONNECTED).build();
        String url = "https://www.instagram.com/reel/abc123/";

        // when
        ReelMetrics m1 = provider.fetchReelMetrics(conn, url);
        ReelMetrics m2 = provider.fetchReelMetrics(conn, url);

        // then — 같은 URL → 같은 지표(결정적), shares 양수
        assertThat(m1).isEqualTo(m2);
        assertThat(m1.views()).isPositive();
        assertThat(m1.shares()).isPositive();
    }
}

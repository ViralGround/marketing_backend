package com.viralground.backend.instagram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockInstagramMetricsProviderTest {

    private final MockInstagramMetricsProvider provider = new MockInstagramMetricsProvider();

    @Test
    void 같은_URL_은_항상_같은_지표를_반환한다() {
        // given / when
        ReelMetrics m1 = provider.fetch("https://www.instagram.com/reel/abc123/");
        ReelMetrics m2 = provider.fetch("https://www.instagram.com/reel/abc123/");

        // then — 결정적이라 record 동등성 성립
        assertThat(m1).isEqualTo(m2);
        assertThat(m1.views()).isPositive();
        assertThat(m1.likes()).isPositive();
        assertThat(m1.dailyViews()).hasSize(14);
    }

    @Test
    void 공유수는_좋아요의_5에서_15퍼센트_범위로_채워진다() {
        // when
        ReelMetrics m = provider.fetch("https://www.instagram.com/reel/abc123/");

        // then — shares 는 likes 의 5~15% 시드값
        assertThat(m.shares()).isBetween(
                (long) (m.likes() * 0.05), (long) (m.likes() * 0.15));
        assertThat(m.shares()).isPositive();
    }

    @Test
    void 다른_URL_은_다른_조회수를_반환한다() {
        // when
        ReelMetrics m1 = provider.fetch("https://www.instagram.com/reel/abc123/");
        ReelMetrics m2 = provider.fetch("https://www.instagram.com/reel/xyz789/");

        // then
        assertThat(m1.views()).isNotEqualTo(m2.views());
    }
}

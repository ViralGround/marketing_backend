package com.viralground.backend.instagram.phyllo;

import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.instagram.ReelMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PhylloInstagramConnectionProvider#fetchReelMetrics} 의 매칭·매핑 로직 검증.
 * PhylloClient(HTTP) 는 mock 으로 대체해 순수 로직만 본다.
 */
class PhylloInstagramConnectionProviderTest {

    private final PhylloClient client = mock(PhylloClient.class);
    private final PhylloProperties props = new PhylloProperties(
            "id", "secret", "https://api.staging.getphyllo.com", "wh", "staging", List.of("ENGAGEMENT"));
    private final PhylloInstagramConnectionProvider provider =
            new PhylloInstagramConnectionProvider(client, props);

    private CreatorInstagramConnection connection() {
        return CreatorInstagramConnection.builder()
                .creatorId(7)
                .providerAccountId("acc-1")
                .build();
    }

    @Test
    void shortcode_로_매칭해_인게이지먼트를_ReelMetrics_로_매핑한다() {
        // given — 여러 콘텐츠 중 shortcode 가 일치하는 것을 골라야 한다
        when(client.fetchContents("acc-1")).thenReturn(List.of(
                new PhylloContent("https://www.instagram.com/reel/OTHER/", "m0", "VIDEO",
                        new PhylloEngagement(1L, 1L, 1L, 1L)),
                new PhylloContent("https://www.instagram.com/reel/ABC123/", "m1", "VIDEO",
                        new PhylloEngagement(100L, 20L, 1000L, 8L))));

        // when — 쿼리스트링이 붙어도 매칭돼야 한다
        ReelMetrics m = provider.fetchReelMetrics(connection(),
                "https://www.instagram.com/reel/ABC123/?igshid=xyz");

        // then
        assertThat(m.views()).isEqualTo(1000L);
        assertThat(m.likes()).isEqualTo(100L);
        assertThat(m.comments()).isEqualTo(20L);
        assertThat(m.shares()).isEqualTo(8L);
    }

    @Test
    void 누락된_인게이지먼트_필드는_0_으로_처리한다() {
        when(client.fetchContents("acc-1")).thenReturn(List.of(
                new PhylloContent("https://www.instagram.com/reel/ABC123/", "m1", "VIDEO",
                        new PhylloEngagement(null, null, null, null))));

        ReelMetrics m = provider.fetchReelMetrics(connection(), "https://www.instagram.com/reel/ABC123/");

        assertThat(m.views()).isZero();
        assertThat(m.likes()).isZero();
        assertThat(m.comments()).isZero();
        assertThat(m.shares()).isZero();
    }

    @Test
    void 매칭되는_릴스가_없으면_예외() {
        when(client.fetchContents("acc-1")).thenReturn(List.of(
                new PhylloContent("https://www.instagram.com/reel/OTHER/", "m1", "VIDEO",
                        new PhylloEngagement(1L, 1L, 1L, 1L))));

        assertThatThrownBy(() -> provider.fetchReelMetrics(connection(),
                "https://www.instagram.com/reel/ABC123/"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 릴스_URL_형식이_아니면_예외() {
        assertThatThrownBy(() -> provider.fetchReelMetrics(connection(), "not-a-url"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 연결_계정_id_가_없으면_예외() {
        CreatorInstagramConnection noAccount = CreatorInstagramConnection.builder().creatorId(7).build();

        assertThatThrownBy(() -> provider.fetchReelMetrics(noAccount,
                "https://www.instagram.com/reel/ABC123/"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fetchAccountUsername_은_PhylloClient_조회를_위임한다() {
        when(client.fetchUsername("acc-1")).thenReturn("creator.ig");

        assertThat(provider.fetchAccountUsername("acc-1")).isEqualTo("creator.ig");
    }

    @Test
    void findConnectedAccountId_는_PhylloClient_조회를_위임한다() {
        when(client.findConnectedAccountId("user-1")).thenReturn(Optional.of("acct-9"));

        assertThat(provider.findConnectedAccountId("user-1")).contains("acct-9");
    }
}

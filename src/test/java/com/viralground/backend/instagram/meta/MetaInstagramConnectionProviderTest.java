package com.viralground.backend.instagram.meta;

import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.InstagramTokenCipher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetaInstagramConnectionProviderTest {

    private final Instant now = Instant.parse("2026-08-13T02:00:00Z");
    private final MetaInstagramProperties properties = new MetaInstagramProperties(
            "app-id", "app-secret", "https://api.example/callback", "https://web.example/result",
            "ignored", "verify", "v25.0", "https://instagram.example/oauth/authorize",
            "https://api.instagram.example", "https://graph.instagram.example",
            List.of("instagram_business_basic", "instagram_business_manage_insights"),
            Duration.ofMinutes(10), Duration.ofSeconds(3), Duration.ofSeconds(8),
            Duration.ZERO, Duration.ofDays(7), 3, 50, 2, 14);
    private final MetaInstagramClient client = mock(MetaInstagramClient.class);
    private final InstagramTokenCipher cipher = mock(InstagramTokenCipher.class);
    private final MetaInstagramConnectionProvider provider = new MetaInstagramConnectionProvider(
            properties, client, cipher, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void authorizationUrlContainsExactRedirectMinimalScopesAndState() {
        String url = provider.buildAuthorizationUrl("state-value", "creator");

        assertThat(url).startsWith("https://instagram.example/oauth/authorize?")
                .contains("client_id=app-id")
                .contains("redirect_uri=https://api.example/callback")
                .contains("response_type=code")
                .contains("instagram_business_basic")
                .contains("instagram_business_manage_insights")
                .contains("state=state-value")
                .doesNotContain("app-secret");
    }

    @Test
    void codeExchangeFailsClosedIfTokenOwnerAndMeAccountDiffer() {
        when(client.exchangeCode("code")).thenReturn(new MetaInstagramClient.ShortToken("short", "account-a"));
        when(client.exchangeLongLived("short")).thenReturn(
                new MetaInstagramClient.LongToken("long", now.plus(Duration.ofDays(60))));
        when(client.fetchCurrentAccount("long")).thenReturn(
                new MetaInstagramClient.Account("account-b", "creator", "CREATOR"));

        assertThatThrownBy(() -> provider.exchangeAuthorizationCode("code"))
                .isInstanceOf(InstagramIntegrationException.class)
                .extracting("code").isEqualTo("INSTAGRAM_ACCOUNT_OWNERSHIP_FAILED");
        verify(client).revoke("account-b", "long");
    }

    @Test
    void metricsOnlyAcceptMediaOwnedByConnectedAccountAndBoundPages() {
        CreatorInstagramConnection connection = CreatorInstagramConnection.builder()
                .creatorId(7).providerAccountId("account-a").encryptedAccessToken("cipher")
                .accessTokenExpiresAt(LocalDateTime.ofInstant(now.plus(Duration.ofDays(30)), ZoneOffset.UTC))
                .build();
        when(cipher.decrypt("cipher")).thenReturn("token");
        when(client.fetchMedia("account-a", "token", null)).thenReturn(
                new MetaInstagramClient.MediaPage(List.of(
                        new MetaInstagramClient.Media("media-1", "https://instagram.com/reel/OTHER/", "VIDEO")),
                        "next"));
        when(client.fetchMedia("account-a", "token", "next")).thenReturn(
                new MetaInstagramClient.MediaPage(List.of(
                        new MetaInstagramClient.Media("media-2", "https://instagram.com/reel/TARGET/", "REELS")),
                        null));
        when(client.fetchMediaCounts("media-2", "token"))
                .thenReturn(new MetaInstagramClient.MediaCounts(100, 12));
        when(client.fetchMediaInsights("media-2", "token"))
                .thenReturn(new MetaInstagramClient.InsightCounts(1000, 7));

        var metrics = provider.fetchReelMetrics(connection, "https://instagram.com/reel/TARGET/");

        assertThat(metrics.views()).isEqualTo(1000);
        assertThat(metrics.likes()).isEqualTo(100);
        assertThat(metrics.comments()).isEqualTo(12);
        assertThat(metrics.shares()).isEqualTo(7);
        verify(client).fetchMedia("account-a", "token", "next");
    }

    @Test
    void refreshesNearExpiryTokenAndUpdatesEncryptedFields() {
        CreatorInstagramConnection connection = CreatorInstagramConnection.builder()
                .creatorId(7).providerAccountId("account-a").encryptedAccessToken("old-cipher")
                .accessTokenExpiresAt(LocalDateTime.ofInstant(now.plus(Duration.ofDays(3)), ZoneOffset.UTC))
                .build();
        when(cipher.decrypt("old-cipher")).thenReturn("old-token");
        when(client.refreshLongLived("old-token")).thenReturn(
                new MetaInstagramClient.LongToken("new-token", now.plus(Duration.ofDays(60))));
        when(cipher.encrypt("new-token")).thenReturn("new-cipher");
        when(client.fetchMedia("account-a", "new-token", null)).thenReturn(
                new MetaInstagramClient.MediaPage(List.of(
                        new MetaInstagramClient.Media("media", "https://instagram.com/reel/TARGET/", "REELS")), null));
        when(client.fetchMediaCounts("media", "new-token")).thenReturn(new MetaInstagramClient.MediaCounts(1, 2));
        when(client.fetchMediaInsights("media", "new-token")).thenReturn(new MetaInstagramClient.InsightCounts(3, 4));

        provider.fetchReelMetrics(connection, "https://instagram.com/reel/TARGET/");

        assertThat(connection.getEncryptedAccessToken()).isEqualTo("new-cipher");
        assertThat(connection.getAccessTokenExpiresAt())
                .isEqualTo(LocalDateTime.ofInstant(now.plus(Duration.ofDays(60)), ZoneOffset.UTC));
    }
}

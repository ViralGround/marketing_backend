package com.viralground.backend.instagram.meta;

import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.instagram.InstagramConnectionProvider;
import com.viralground.backend.instagram.InstagramIntegrationException;
import com.viralground.backend.instagram.InstagramTokenCipher;
import com.viralground.backend.instagram.InstagramUrl;
import com.viralground.backend.instagram.ReelMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
@ConditionalOnProperty(name = "instagram.provider", havingValue = "meta")
public class MetaInstagramConnectionProvider implements InstagramConnectionProvider {

    private static final Logger log = LoggerFactory.getLogger(MetaInstagramConnectionProvider.class);

    private final MetaInstagramProperties properties;
    private final MetaInstagramClient client;
    private final InstagramTokenCipher tokenCipher;
    private final Clock clock;

    public MetaInstagramConnectionProvider(MetaInstagramProperties properties,
                                           InstagramTokenCipher tokenCipher,
                                           Clock clock) {
        properties.requireConfigured();
        this.properties = properties;
        this.client = new MetaInstagramClient(properties, clock);
        this.tokenCipher = tokenCipher;
        this.clock = clock;
    }

    MetaInstagramConnectionProvider(MetaInstagramProperties properties,
                                    MetaInstagramClient client,
                                    InstagramTokenCipher tokenCipher,
                                    Clock clock) {
        this.properties = properties;
        this.client = client;
        this.tokenCipher = tokenCipher;
        this.clock = clock;
    }

    @Override
    public String buildAuthorizationUrl(String state, String profileHandle) {
        return UriComponentsBuilder.fromUriString(properties.authorizationUrl())
                .queryParam("enable_fb_login", "0")
                .queryParam("force_authentication", "1")
                .queryParam("client_id", properties.appId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(",", properties.scopes()))
                .queryParam("state", state)
                .build().encode().toUriString();
    }

    @Override
    public AuthorizationResult exchangeAuthorizationCode(String code) {
        if (code == null || code.isBlank()) {
            throw new InstagramIntegrationException("INSTAGRAM_CODE_MISSING",
                    "인스타그램 인증 코드가 없습니다", HttpStatus.BAD_REQUEST);
        }
        MetaInstagramClient.ShortToken shortToken = client.exchangeCode(code);
        MetaInstagramClient.LongToken longToken = client.exchangeLongLived(shortToken.accessToken());
        MetaInstagramClient.Account account = client.fetchCurrentAccount(longToken.accessToken());
        if (!shortToken.accountId().equals(account.id())) {
            try {
                client.revoke(account.id(), longToken.accessToken());
            } catch (RuntimeException revokeFailure) {
                log.error("event=instagram_ownership_failure_revoke_failed", revokeFailure);
            }
            throw new InstagramIntegrationException("INSTAGRAM_ACCOUNT_OWNERSHIP_FAILED",
                    "인스타그램 계정 소유권을 확인하지 못했습니다", HttpStatus.BAD_REQUEST);
        }
        return new AuthorizationResult(account.id(), account.username(),
                longToken.accessToken(), longToken.expiresAt());
    }

    @Override
    public ReelMetrics fetchReelMetrics(CreatorInstagramConnection connection, String reelUrl) {
        String expectedShortcode = InstagramUrl.shortcode(reelUrl)
                .orElseThrow(() -> new InstagramIntegrationException("INVALID_INSTAGRAM_URL",
                        "Instagram Reel URL 형식을 확인해 주세요", HttpStatus.BAD_REQUEST));
        String token = usableToken(connection);
        String cursor = null;
        MetaInstagramClient.Media matched = null;
        for (int page = 0; page < properties.maxMediaPages(); page++) {
            MetaInstagramClient.MediaPage mediaPage = client.fetchMedia(
                    requiredAccountId(connection), token, cursor);
            matched = mediaPage.media().stream()
                    .filter(media -> InstagramUrl.shortcode(media.permalink())
                            .filter(expectedShortcode::equals).isPresent())
                    .findFirst().orElse(null);
            if (matched != null || mediaPage.after() == null || mediaPage.after().isBlank()) {
                break;
            }
            cursor = mediaPage.after();
        }
        if (matched == null) {
            throw new InstagramIntegrationException("INSTAGRAM_MEDIA_NOT_OWNED",
                    "연결된 계정에서 해당 Reel을 찾지 못했습니다", HttpStatus.BAD_REQUEST);
        }
        MetaInstagramClient.MediaCounts counts = client.fetchMediaCounts(matched.id(), token);
        MetaInstagramClient.InsightCounts insights = client.fetchMediaInsights(matched.id(), token);
        log.info("event=instagram_metrics_synced creatorId={} views={} likes={} comments={} shares={}",
                connection.getCreatorId(), insights.views(), counts.likes(), counts.comments(), insights.shares());
        return new ReelMetrics(insights.views(), counts.likes(), counts.comments(), insights.shares(), List.of());
    }

    @Override
    public void revoke(AuthorizationResult authorization) {
        client.revoke(authorization.accountId(), authorization.accessToken());
    }

    @Override
    public void revoke(CreatorInstagramConnection connection) {
        if (connection.getEncryptedAccessToken() == null || connection.getProviderAccountId() == null) {
            return;
        }
        client.revoke(connection.getProviderAccountId(), tokenCipher.decrypt(connection.getEncryptedAccessToken()));
        log.info("event=instagram_permission_revoked creatorId={}", connection.getCreatorId());
    }

    private String usableToken(CreatorInstagramConnection connection) {
        if (connection.getEncryptedAccessToken() == null || connection.getAccessTokenExpiresAt() == null) {
            throw new InstagramIntegrationException("INSTAGRAM_RECONNECT_REQUIRED",
                    "인스타그램을 다시 연결해 주세요", HttpStatus.CONFLICT);
        }
        Instant now = clock.instant();
        Instant expiresAt = connection.getAccessTokenExpiresAt().toInstant(ZoneOffset.UTC);
        if (!expiresAt.isAfter(now)) {
            throw new InstagramIntegrationException("INSTAGRAM_TOKEN_EXPIRED",
                    "인스타그램 연결이 만료되었습니다. 다시 연결해 주세요", HttpStatus.CONFLICT);
        }
        String token = tokenCipher.decrypt(connection.getEncryptedAccessToken());
        if (!expiresAt.isAfter(now.plus(properties.refreshBeforeExpiry()))) {
            MetaInstagramClient.LongToken refreshed = client.refreshLongLived(token);
            token = refreshed.accessToken();
            connection.setEncryptedAccessToken(tokenCipher.encrypt(token));
            connection.setAccessTokenExpiresAt(LocalDateTime.ofInstant(refreshed.expiresAt(), ZoneOffset.UTC));
            connection.setTokenRefreshedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            log.info("event=instagram_token_refreshed creatorId={}", connection.getCreatorId());
        }
        return token;
    }

    private static String requiredAccountId(CreatorInstagramConnection connection) {
        if (connection.getProviderAccountId() == null || connection.getProviderAccountId().isBlank()) {
            throw new InstagramIntegrationException("INSTAGRAM_RECONNECT_REQUIRED",
                    "인스타그램을 다시 연결해 주세요", HttpStatus.CONFLICT);
        }
        return connection.getProviderAccountId();
    }
}

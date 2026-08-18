package com.viralground.backend.instagram.meta;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "meta.instagram")
public record MetaInstagramProperties(
        String appId,
        String appSecret,
        String redirectUri,
        String frontendResultUrl,
        String tokenEncryptionKey,
        String webhookVerifyToken,
        String apiVersion,
        String authorizationUrl,
        String oauthBaseUrl,
        String graphBaseUrl,
        List<String> scopes,
        Duration stateTtl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration retryBackoff,
        Duration refreshBeforeExpiry,
        int maxAttempts,
        int mediaPageSize,
        int maxMediaPages,
        int webhookRetentionDays) {

    public MetaInstagramProperties {
        apiVersion = defaultString(apiVersion, "v25.0");
        authorizationUrl = defaultString(authorizationUrl, "https://www.instagram.com/oauth/authorize");
        oauthBaseUrl = defaultString(oauthBaseUrl, "https://api.instagram.com");
        graphBaseUrl = defaultString(graphBaseUrl, "https://graph.instagram.com");
        scopes = scopes == null || scopes.isEmpty()
                ? List.of("instagram_business_basic", "instagram_business_manage_insights")
                : List.copyOf(scopes);
        stateTtl = stateTtl == null ? Duration.ofMinutes(10) : stateTtl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(8) : readTimeout;
        retryBackoff = retryBackoff == null ? Duration.ofMillis(250) : retryBackoff;
        refreshBeforeExpiry = refreshBeforeExpiry == null ? Duration.ofDays(7) : refreshBeforeExpiry;
        maxAttempts = maxAttempts < 1 ? 3 : Math.min(maxAttempts, 5);
        mediaPageSize = mediaPageSize < 1 ? 50 : Math.min(mediaPageSize, 100);
        maxMediaPages = maxMediaPages < 1 ? 3 : Math.min(maxMediaPages, 10);
        webhookRetentionDays = webhookRetentionDays < 1 ? 14 : Math.min(webhookRetentionDays, 90);
    }

    public void requireConfigured() {
        require("META_INSTAGRAM_APP_ID", appId);
        require("META_INSTAGRAM_APP_SECRET", appSecret);
        require("META_INSTAGRAM_REDIRECT_URI", redirectUri);
        require("META_INSTAGRAM_FRONTEND_RESULT_URL", frontendResultUrl);
        require("META_INSTAGRAM_TOKEN_ENCRYPTION_KEY", tokenEncryptionKey);
        require("META_INSTAGRAM_WEBHOOK_VERIFY_TOKEN", webhookVerifyToken);
        if (!redirectUri.startsWith("https://") && !redirectUri.startsWith("http://localhost")) {
            throw new IllegalStateException("META_INSTAGRAM_REDIRECT_URI는 HTTPS URL이어야 합니다");
        }
        if (!frontendResultUrl.startsWith("https://") && !frontendResultUrl.startsWith("http://localhost")) {
            throw new IllegalStateException("META_INSTAGRAM_FRONTEND_RESULT_URL은 HTTPS URL이어야 합니다");
        }
        java.net.URI redirect = parseUri("META_INSTAGRAM_REDIRECT_URI", redirectUri);
        java.net.URI frontend = parseUri("META_INSTAGRAM_FRONTEND_RESULT_URL", frontendResultUrl);
        if (redirect.getHost() == null || frontend.getHost() == null
                || redirect.getUserInfo() != null || frontend.getUserInfo() != null) {
            throw new IllegalStateException("Meta Instagram URL에는 유효한 host가 필요하고 userinfo를 포함할 수 없습니다");
        }
        if (webhookVerifyToken.length() < 32) {
            throw new IllegalStateException("META_INSTAGRAM_WEBHOOK_VERIFY_TOKEN은 32자 이상이어야 합니다");
        }
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 환경변수가 필요합니다");
        }
    }

    private static java.net.URI parseUri(String name, String value) {
        try {
            return java.net.URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " URL 형식이 올바르지 않습니다", e);
        }
    }
}

package com.viralground.backend.instagram.meta;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

/**
 * 활성 Meta 연동이 공식 provider endpoint와 승인된 callback topology 밖으로
 * 자격증명이나 authorization code를 보내지 못하게 기동 시 fail-closed 한다.
 */
@Component
@ConditionalOnProperty(name = "features.instagram.enabled", havingValue = "true")
public class MetaInstagramSafetyValidator implements InitializingBean {

    static final String STAGING_REDIRECT_URI =
            "https://api.staging.viralground.kr/instagram/meta/oauth/callback";
    static final String STAGING_FRONTEND_RESULT_URL =
            "https://staging.viralground.kr/creator/mypage";

    private final MetaInstagramProperties properties;
    private final Environment environment;
    private final String provider;

    public MetaInstagramSafetyValidator(
            MetaInstagramProperties properties,
            Environment environment,
            @Value("${instagram.provider:mock}") String provider) {
        this.properties = properties;
        this.environment = environment;
        this.provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    void validate() {
        // Local mock provider remains usable. Protected environments independently
        // require provider=meta in InstagramProviderSafetyValidator.
        if (!"meta".equals(provider)) return;

        properties.requireConfigured();
        requireOfficialUrl("META_INSTAGRAM_AUTHORIZATION_URL", properties.authorizationUrl(),
                "www.instagram.com", "/oauth/authorize");
        requireOfficialUrl("META_INSTAGRAM_OAUTH_BASE_URL", properties.oauthBaseUrl(),
                "api.instagram.com", "");
        requireOfficialUrl("META_INSTAGRAM_GRAPH_BASE_URL", properties.graphBaseUrl(),
                "graph.instagram.com", "");

        if (isStaging()) {
            requireExactStagingUrl("META_INSTAGRAM_REDIRECT_URI", properties.redirectUri(),
                    STAGING_REDIRECT_URI);
            requireExactStagingUrl("META_INSTAGRAM_FRONTEND_RESULT_URL",
                    properties.frontendResultUrl(), STAGING_FRONTEND_RESULT_URL);
        }
    }

    private static void requireOfficialUrl(String name, String value,
                                           String expectedHost, String expectedPath) {
        URI uri = parseUri(name, value);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !expectedHost.equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !expectedPath.equals(path)) {
            throw new IllegalStateException(
                    name + "은 allowlist에 등록된 공식 Meta HTTPS endpoint여야 합니다.");
        }
    }

    private static void requireExactStagingUrl(String name, String value, String expected) {
        String configured = value == null ? "" : value.trim();
        URI uri = parseUri(name, configured);
        URI expectedUri = URI.create(expected);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !expectedUri.getHost().equalsIgnoreCase(uri.getHost())
                || !expectedUri.getRawPath().equals(uri.getRawPath())
                || !expected.equals(configured)) {
            throw new IllegalStateException(name + "은 staging exact topology와 path를 사용해야 합니다: "
                    + expected);
        }
    }

    private boolean isStaging() {
        String appEnvironment = environment.getProperty("app.environment", "")
                .trim().toLowerCase(Locale.ROOT);
        if (appEnvironment.equals("preproduction") || appEnvironment.equals("preprod")
                || appEnvironment.equals("staging")) {
            return true;
        }
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("preproduction")
                        || profile.equalsIgnoreCase("preprod")
                        || profile.equalsIgnoreCase("staging"));
    }

    private static URI parseUri(String name, String value) {
        try {
            return URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " URL 형식이 올바르지 않습니다.", e);
        }
    }
}

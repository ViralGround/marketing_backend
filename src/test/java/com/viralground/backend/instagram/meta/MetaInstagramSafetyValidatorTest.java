package com.viralground.backend.instagram.meta;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaInstagramSafetyValidatorTest {

    @Test
    void acceptsOfficialEndpointsAndExactStagingTopology() {
        MetaInstagramProperties properties = properties(
                MetaInstagramSafetyValidator.STAGING_REDIRECT_URI,
                MetaInstagramSafetyValidator.STAGING_FRONTEND_RESULT_URL,
                "https://www.instagram.com/oauth/authorize",
                "https://api.instagram.com",
                "https://graph.instagram.com");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction");

        assertThatCode(() -> new MetaInstagramSafetyValidator(
                properties, environment, "meta").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOfficialEndpointsWithLocalCallbackDuringDevelopment() {
        MetaInstagramProperties properties = properties(
                "http://localhost:8080/instagram/meta/oauth/callback",
                "http://localhost:3000/creator/mypage",
                "https://www.instagram.com/oauth/authorize",
                "https://api.instagram.com",
                "https://graph.instagram.com");

        assertThatCode(() -> new MetaInstagramSafetyValidator(
                properties, new MockEnvironment(), "meta").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAuthorizationHostSuffixAttack() {
        MetaInstagramProperties properties = properties(
                "http://localhost:8080/instagram/meta/oauth/callback",
                "http://localhost:3000/creator/mypage",
                "https://www.instagram.com.attacker.invalid/oauth/authorize",
                "https://api.instagram.com",
                "https://graph.instagram.com");

        assertThatThrownBy(() -> new MetaInstagramSafetyValidator(
                properties, new MockEnvironment(), "meta").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("META_INSTAGRAM_AUTHORIZATION_URL")
                .hasMessageContaining("공식 Meta HTTPS endpoint");
    }

    @Test
    void rejectsAuthorizationUrlWithUserinfoOrQuery() {
        MetaInstagramProperties properties = properties(
                "http://localhost:8080/instagram/meta/oauth/callback",
                "http://localhost:3000/creator/mypage",
                "https://ignored@www.instagram.com/oauth/authorize?next=evil",
                "https://api.instagram.com",
                "https://graph.instagram.com");

        assertThatThrownBy(() -> new MetaInstagramSafetyValidator(
                properties, new MockEnvironment(), "meta").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("META_INSTAGRAM_AUTHORIZATION_URL");
    }

    @Test
    void rejectsNonHttpsOauthBaseUrl() {
        MetaInstagramProperties properties = properties(
                "http://localhost:8080/instagram/meta/oauth/callback",
                "http://localhost:3000/creator/mypage",
                "https://www.instagram.com/oauth/authorize",
                "http://api.instagram.com",
                "https://graph.instagram.com");

        assertThatThrownBy(() -> new MetaInstagramSafetyValidator(
                properties, new MockEnvironment(), "meta").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("META_INSTAGRAM_OAUTH_BASE_URL");
    }

    @Test
    void rejectsGraphBaseUrlWithUnexpectedPath() {
        MetaInstagramProperties properties = properties(
                "http://localhost:8080/instagram/meta/oauth/callback",
                "http://localhost:3000/creator/mypage",
                "https://www.instagram.com/oauth/authorize",
                "https://api.instagram.com",
                "https://graph.instagram.com/proxy");

        assertThatThrownBy(() -> new MetaInstagramSafetyValidator(
                properties, new MockEnvironment(), "meta").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("META_INSTAGRAM_GRAPH_BASE_URL");
    }

    @Test
    void rejectsWrongStagingCallbackHost() {
        MetaInstagramProperties properties = officialProperties(
                "https://api.viralground.kr/instagram/meta/oauth/callback",
                MetaInstagramSafetyValidator.STAGING_FRONTEND_RESULT_URL);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "staging");

        assertThatThrownBy(() -> new MetaInstagramSafetyValidator(
                properties, environment, "meta").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("META_INSTAGRAM_REDIRECT_URI")
                .hasMessageContaining("exact topology");
    }

    @Test
    void rejectsWrongStagingCallbackPath() {
        MetaInstagramProperties properties = officialProperties(
                "https://api.staging.viralground.kr/oauth/callback",
                MetaInstagramSafetyValidator.STAGING_FRONTEND_RESULT_URL);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preprod");

        assertThatThrownBy(() -> new MetaInstagramSafetyValidator(
                properties, environment, "meta").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("META_INSTAGRAM_REDIRECT_URI");
    }

    @Test
    void rejectsStagingFrontendResultUrlWithQuery() {
        MetaInstagramProperties properties = officialProperties(
                MetaInstagramSafetyValidator.STAGING_REDIRECT_URI,
                MetaInstagramSafetyValidator.STAGING_FRONTEND_RESULT_URL + "?source=oauth");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        assertThatThrownBy(() -> new MetaInstagramSafetyValidator(
                properties, environment, "meta").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("META_INSTAGRAM_FRONTEND_RESULT_URL");
    }

    @Test
    void localMockProviderDoesNotRequireMetaSecretsOrUrls() {
        MetaInstagramProperties empty = new MetaInstagramProperties(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, 0, 0, 0, 0);

        assertThatCode(() -> new MetaInstagramSafetyValidator(
                empty, new MockEnvironment(), "mock").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    private static MetaInstagramProperties officialProperties(String redirect, String frontend) {
        return properties(redirect, frontend,
                "https://www.instagram.com/oauth/authorize",
                "https://api.instagram.com",
                "https://graph.instagram.com");
    }

    private static MetaInstagramProperties properties(String redirect, String frontend,
                                                       String authorizationUrl,
                                                       String oauthBaseUrl,
                                                       String graphBaseUrl) {
        return new MetaInstagramProperties(
                "app-id", "app-secret", redirect, frontend,
                "dGVzdC1lbmNyeXB0aW9uLWtleS0zMi1ieXRlcw==",
                "12345678901234567890123456789012",
                "v25.0", authorizationUrl, oauthBaseUrl, graphBaseUrl,
                List.of("instagram_business_basic"), Duration.ofMinutes(10),
                Duration.ofSeconds(3), Duration.ofSeconds(8), Duration.ofMillis(250),
                Duration.ofDays(7), 3, 50, 3, 14);
    }
}

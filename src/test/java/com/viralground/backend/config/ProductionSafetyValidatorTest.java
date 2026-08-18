package com.viralground.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyValidatorTest {

    @Test
    void developmentAllowsLocalSafeDefaults() {
        assertThatCode(() -> new ProductionSafetyValidator(new MockEnvironment()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void productionRejectsLocalStorage() {
        MockEnvironment env = validProduction().withProperty("files.storage", "local");
        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("객체 저장소");
    }

    @Test
    void productionAllowsDisabledPaymentsForManagedBeta() {
        assertThatCode(() -> new ProductionSafetyValidator(validProduction()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void productionRejectsInsecureAuthenticationCookies() {
        MockEnvironment env = validProduction().withProperty("auth.cookie.secure", "false");
        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_COOKIE_SECURE");
    }

    @Test
    void productionRequiresCookieDomainSharedWithFrontend() {
        MockEnvironment env = validProduction().withProperty("auth.cookie.domain", ".unrelated.test");
        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_URL");
    }

    @Test
    void productionRejectsPlaceholderExternalUrls() {
        MockEnvironment env = validProduction().withProperty("sentry.dsn",
                "https://xxxxxxxx@xxx.ingest.sentry.io/xxxxxxx");
        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sentry.dsn");
    }

    @Test
    void productionRejectsSharedJwtAndFileSigningSecret() {
        MockEnvironment env = validProduction()
                .withProperty("files.signing-secret", "production-jwt-secret-longer-than-32-chars");
        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("서로 다른");
    }

    @Test
    void productionRejectsInsecureCorsOrigin() {
        MockEnvironment env = validProduction().withProperty("cors.allowed-origins", "http://viralground.kr");
        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS");
    }

    @Test
    void productionRejectsPartialOrWeakAdminBootstrap() {
        MockEnvironment env = validProduction()
                .withProperty("admin.bootstrap.email", "admin@viralground.kr")
                .withProperty("admin.bootstrap.password", "short");
        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12자");
    }

    @Test
    void productionRejectsDevelopmentSchemaMutationMode() {
        MockEnvironment env = validProduction()
                .withProperty("spring.jpa.hibernate.ddl-auto", "update");
        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ddl-auto");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "re_example", "re_xxxxxxxxxxxxxxxxxxxxxxxx", "replace-me"})
    void productionRejectsMissingOrPlaceholderResendApiKey(String apiKey) {
        MockEnvironment env = validProduction().withProperty("resend.api-key", apiKey);

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "onboarding@resend.dev", "noreply@yourdomain.com",
            "noreply@example.test", "noreply@gmail.com", "noreply@unrelated.co.kr", "not-an-email"
    })
    void productionRejectsNonCompanyEmailFrom(String emailFrom) {
        MockEnvironment env = validProduction().withProperty("resend.from", emailFrom);

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL_FROM");
    }

    @Test
    void productionAcceptsDisplayNameWithVerifiedCompanyDomain() {
        MockEnvironment env = validProduction()
                .withProperty("resend.from", "ViralGround <noreply@viralground.kr>");

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v2-draft", "placeholder"})
    void productionRejectsNonFinalLegalDocumentVersions(String version) {
        MockEnvironment env = validProduction()
                .withProperty("legal.documents.terms-version", version);

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("법적 문서");
    }

    @ParameterizedTest
    @ValueSource(strings = {"legal.privacy-officer.name", "legal.privacy-officer.contact"})
    void productionRejectsMissingPrivacyOfficerIdentityOrContact(String property) {
        MockEnvironment env = validProduction().withProperty(property, "");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(property);
    }

    private MockEnvironment validProduction() {
        return new MockEnvironment()
                .withProperty("app.environment", "production")
                .withProperty("app.url", "https://viralground.kr")
                .withProperty("files.public-base-url", "https://api.viralground.kr")
                .withProperty("cors.allowed-origins", "https://viralground.kr")
                .withProperty("jwt.secret", "production-jwt-secret-longer-than-32-chars")
                .withProperty("files.signing-secret", "independent-file-signing-secret-over-32-chars")
                .withProperty("resend.api-key", "re_live_8Rj2k6m9Q4s7V1x5Z3c0")
                .withProperty("resend.from", "noreply@viralground.kr")
                .withProperty("sentry.dsn", "https://key@o123.ingest.sentry.io/1")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("legal.documents.terms-version", "terms-2026-08-13")
                .withProperty("legal.documents.privacy-version", "privacy-2026-08-13")
                .withProperty("legal.documents.age14-version", "age14-2026-08-13")
                .withProperty("legal.documents.creator-third-party-version", "third-party-2026-08-13")
                .withProperty("legal.documents.marketing-version", "marketing-2026-08-13")
                .withProperty("legal.privacy-officer.name", "개인정보 보호책임자")
                .withProperty("legal.privacy-officer.contact", "privacy@viralground.example")
                .withProperty("email.mock", "false")
                .withProperty("files.storage", "s3")
                .withProperty("payments.gateway", "disabled")
                .withProperty("auth.cookie.secure", "true")
                .withProperty("auth.cookie.domain", ".viralground.kr")
                .withProperty("auth.cookie.same-site", "Lax");
    }
}

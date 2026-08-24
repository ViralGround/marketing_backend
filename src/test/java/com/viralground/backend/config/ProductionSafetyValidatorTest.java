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
    void developmentCannotReachRemoteDatabaseWhenFlywayIsDisabledForAdminBootstrap() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.environment", "development")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://remote-db.example.test:5432/viralground")
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("admin.bootstrap.enabled", "true")
                .withProperty("admin.bootstrap.email", "admin@viralground.kr")
                .withProperty("admin.bootstrap.password", "StrongRemoteSecret!2026")
                .withProperty("admin.bootstrap.name", "Must Not Run");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remote PostgreSQL");
    }

    @Test
    void testEnvironmentCannotReachRemoteDatabaseForDemoBootstrap() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.environment", "test")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://remote-db.example.test:5432/viralground")
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("demo.bootstrap.enabled", "true");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remote PostgreSQL");
    }

    @Test
    void developmentMayUseOnlyLoopbackPostgreSql() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.environment", "development")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://127.0.0.1:5432/viralground_local");

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void protectedRuntimeRejectsEmbeddedH2Datasource() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:must-not-start");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedded H2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"production ", "Production", "prod", "staging", "pre-production"})
    void ambiguousOrAliasedAppEnvironmentFailsClosed(String appEnvironment) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.environment", appEnvironment);

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENV");
    }

    @Test
    void appEnvironmentCannotBeWeakenedByAConflictingProtectedProfile() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.environment", "production");
        env.setActiveProfiles("preproduction");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly match");
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
    void productionRejectsAdminBootstrapAbsolutely() {
        MockEnvironment env = validProduction()
                .withProperty("admin.bootstrap.enabled", "true")
                .withProperty("admin.bootstrap.email", "admin@viralground.kr")
                .withProperty("admin.bootstrap.password", "short");
        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("절대");
    }

    @Test
    void preproductionDisabledAdminBootstrapRequiresEveryCredentialBlank() {
        MockEnvironment env = validPreproduction()
                .withProperty("admin.bootstrap.enabled", "false")
                .withProperty("admin.bootstrap.name", "leftover-name");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("모두 비워야");
    }

    @Test
    void preproductionAllowsExplicitOneShotAdminBootstrap() {
        MockEnvironment env = validProvisioning()
                .withProperty("app.staging.provisioning-allowed-emails",
                        "qa-admin@viralground.kr")
                .withProperty("email.allowed-recipients", "qa-admin@viralground.kr")
                .withProperty("admin.bootstrap.enabled", "true")
                .withProperty("admin.bootstrap.confirmation",
                        "CREATE_ONE_PREPRODUCTION_ADMIN_ONCE")
                .withProperty("admin.bootstrap.email", "qa-admin@viralground.kr")
                .withProperty("admin.bootstrap.password", "StrongOneShotSecret!2026")
                .withProperty("admin.bootstrap.name", "QA Admin");

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void preproductionAdminBootstrapRequiresExactOneShotConfirmation() {
        MockEnvironment env = validProvisioning()
                .withProperty("app.staging.provisioning-allowed-emails",
                        "qa-admin@viralground.kr")
                .withProperty("email.allowed-recipients", "qa-admin@viralground.kr")
                .withProperty("admin.bootstrap.enabled", "true")
                .withProperty("admin.bootstrap.confirmation", "yes")
                .withProperty("admin.bootstrap.email", "qa-admin@viralground.kr")
                .withProperty("admin.bootstrap.password", "StrongOneShotSecret!2026")
                .withProperty("admin.bootstrap.name", "QA Admin");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation phrase");
    }

    @Test
    void preproductionMutationModesAreMutuallyExclusive() {
        MockEnvironment env = validPreproduction()
                .withProperty("app.staging.account-provisioning-enabled", "true")
                .withProperty("app.staging.e2e-mutation-enabled", "true")
                .withProperty("app.staging.provisioning-allowed-emails",
                        "creator.qa@viralground.kr")
                .withProperty(
                        "app.preproduction-database.e2e-before-evidence-seal-sha256",
                        "c".repeat(64));

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void provisioningRequiresExactLowercaseEmailAllowlistAndNoBeforeSeal() {
        MockEnvironment env = validProvisioning()
                .withProperty("app.staging.provisioning-allowed-emails", "Creator@viralground.kr");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact lowercase");
    }

    @Test
    void e2eMutationModeRequiresBeforeEvidenceSealAndBlankProvisioningAllowlist() {
        MockEnvironment env = validPreproduction()
                .withProperty("app.staging.e2e-mutation-enabled", "true");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("E2E-before evidence seal");
    }

    @Test
    void validProvisioningAndE2eModesAreAcceptedSeparately() {
        MockEnvironment provisioning = validProvisioning()
                .withProperty("app.staging.provisioning-allowed-emails",
                        "creator.qa@viralground.kr,company.qa@viralground.kr")
                .withProperty("email.allowed-recipients",
                        "creator.qa@viralground.kr,company.qa@viralground.kr");
        MockEnvironment e2e = validE2e();

        assertThatCode(() -> new ProductionSafetyValidator(provisioning).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatCode(() -> new ProductionSafetyValidator(e2e).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void sealedEmailValidationWindowIsAcceptedSeparately() {
        assertThatCode(() -> new ProductionSafetyValidator(
                validEmailValidation()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void allowlistEmailRequiresAnExplicitProvisioningOrEmailValidationWindow() {
        MockEnvironment env = validPreproduction()
                .withProperty("email.delivery-mode", "allowlist")
                .withProperty("email.allowed-recipients", "qa@viralground.kr")
                .withProperty("app.scheduling.enabled", "true")
                .withProperty("notification.outbox.dispatch-enabled", "true");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit provisioning or email validation window");
    }

    @Test
    void emailValidationRequiresLiveBeforeSealConfiguration() {
        MockEnvironment env = validEmailValidation()
                .withProperty(
                        "app.preproduction-database.e2e-before-evidence-seal-sha256", "");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("E2E-before evidence seal");
    }

    @Test
    void emailValidationRejectsExternalRecipientAndAnyOtherFeatureWindow() {
        MockEnvironment externalRecipient = validEmailValidation()
                .withProperty("email.allowed-recipients", "qa@gmail.com")
                .withProperty("app.staging.email-validation-recipient", "qa@gmail.com");
        MockEnvironment featureEnabled = validEmailValidation()
                .withProperty("features.instagram.enabled", "true");

        assertThatThrownBy(() -> new ProductionSafetyValidator(
                externalRecipient).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("viralground.kr");
        assertThatThrownBy(() -> new ProductionSafetyValidator(
                featureEnabled).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payments/Instagram/uploads disabled");
    }

    @Test
    void emailValidationRequiresOneFixedAllowlistedLowercaseRecipient() {
        MockEnvironment missing = validEmailValidation()
                .withProperty("app.staging.email-validation-recipient", "");
        MockEnvironment uppercase = validEmailValidation()
                .withProperty("app.staging.email-validation-recipient", "QA@viralground.kr");
        MockEnvironment notAllowlisted = validEmailValidation()
                .withProperty("app.staging.email-validation-recipient", "other@viralground.kr");

        assertThatThrownBy(() -> new ProductionSafetyValidator(missing).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STAGING_EMAIL_VALIDATION_RECIPIENT");
        assertThatThrownBy(() -> new ProductionSafetyValidator(uppercase).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact lowercase");
        assertThatThrownBy(() -> new ProductionSafetyValidator(notAllowlisted).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL_ALLOWED_RECIPIENTS");
    }

    @Test
    void fixedEmailValidationRecipientIsForbiddenOutsideItsWindow() {
        MockEnvironment idle = validPreproduction()
                .withProperty("app.staging.email-validation-recipient", "qa@viralground.kr");

        assertThatThrownBy(() -> new ProductionSafetyValidator(idle).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank outside email validation mode");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "redis://default:managed-redis-password-2026@cache.viralground.kr:6379",
            "rediss://default:managed-redis-password-2026@localhost:6379",
            "rediss://default:managed-redis-password-2026@127.0.0.1:6379",
            "rediss://default:managed-redis-password-2026@10.20.30.40:6379",
            "rediss://default:managed-redis-password-2026@169.254.1.2:6379",
            "rediss://default:managed-redis-password-2026@cache.internal:6379",
            "rediss://default:managed-redis-password-2026@cache.viralground.kr:6379",
            "rediss://default:managed-redis-password-2026@redis.railway.internal:6379?verifyPeer=NONE",
            "rediss://default:short@redis.railway.internal:6379"
    })
    void protectedRuntimeRejectsUnsafeOrUnapprovedRedisUrl(String redisUrl) {
        MockEnvironment env = validProduction()
                .withProperty("spring.data.redis.url", redisUrl);

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS");
    }

    @Test
    void protectedRuntimeAllowsExplicitExactRedisHost() {
        MockEnvironment env = validProduction()
                .withProperty("spring.data.redis.url",
                        "rediss://default:managed-redis-password-2026@cache.viralground.kr:6380")
                .withProperty("rate-limit.redis-transport", "tls")
                .withProperty("rate-limit.redis-allowed-hosts", "cache.viralground.kr");

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void protectedRuntimeAllowsExplicitRailwayPrivateRedisTransport() {
        MockEnvironment env = validProduction();

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void railwayPrivateRedisRequiresExplicitTransportDeclaration() {
        MockEnvironment env = validProduction()
                .withProperty("rate-limit.redis-transport", "tls");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("railway-private");
    }

    @Test
    void plaintextRedisOutsideRailwayPrivateNetworkIsRejectedEvenWhenAllowlisted() {
        MockEnvironment env = validProduction()
                .withProperty("spring.data.redis.url",
                        "redis://default:managed-redis-password-2026@cache.viralground.kr:6379")
                .withProperty("rate-limit.redis-allowed-hosts", "cache.viralground.kr");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rediss://");
    }

    @Test
    void protectedRuntimeRejectsRedisEnvironmentCopiedFromAnotherEnvironment() {
        MockEnvironment env = validPreproduction()
                .withProperty("rate-limit.redis-environment", "production");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RATE_LIMIT_REDIS_ENVIRONMENT");
    }

    @Test
    void protectedRuntimeRejectsSharedOrWrongRedisKeyPrefix() {
        MockEnvironment env = validPreproduction()
                .withProperty("rate-limit.redis-key-prefix", "viralground:rate-limit");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RATE_LIMIT_REDIS_KEY_PREFIX");
    }

    @Test
    void railwayHostnameStillRequiresEnvironmentExactAllowlist() {
        MockEnvironment env = validProduction()
                .withProperty("rate-limit.redis-allowed-hosts", "different.railway.internal");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_ALLOWED_HOSTS");
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

    @Test
    void preproductionRequiresRecipientAllowlistAndOtherwiseUsesProductionSafetyRules() {
        MockEnvironment env = validEmailValidation()
                .withProperty("features.uploads.enabled", "false")
                .withProperty("files.storage", "disabled");

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void preproductionAllowsDisabledEmailWithoutResendCredentials() {
        MockEnvironment env = validPreproduction()
                .withProperty("email.delivery-mode", "disabled")
                .withProperty("email.allowed-recipients", "")
                .withProperty("resend.api-key", "")
                .withProperty("resend.from", "")
                .withProperty("app.scheduling.enabled", "false")
                .withProperty("notification.outbox.dispatch-enabled", "false");

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void preproductionDisabledEmailRejectsOutboxDispatch() {
        MockEnvironment env = validPreproduction()
                .withProperty("email.delivery-mode", "disabled")
                .withProperty("email.allowed-recipients", "")
                .withProperty("resend.api-key", "")
                .withProperty("resend.from", "")
                .withProperty("app.scheduling.enabled", "true")
                .withProperty("notification.outbox.dispatch-enabled", "true");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox dispatch");
    }

    @Test
    void preproductionDisabledEmailRejectsGlobalScheduling() {
        MockEnvironment env = validPreproduction()
                .withProperty("email.delivery-mode", "disabled")
                .withProperty("email.allowed-recipients", "")
                .withProperty("resend.api-key", "")
                .withProperty("resend.from", "")
                .withProperty("app.scheduling.enabled", "true")
                .withProperty("notification.outbox.dispatch-enabled", "false");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("global scheduling");
    }

    @Test
    void preproductionAllowlistWindowAllowsOutboxDispatchWithGlobalScheduling() {
        MockEnvironment env = validEmailValidation();

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void preproductionAllowlistRejectsDisabledOutbox() {
        MockEnvironment env = validEmailValidation()
                .withProperty("notification.outbox.enabled", "false");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduling and outbox dispatch");
    }

    @Test
    void preproductionAllowlistRejectsStoppedDispatcher() {
        MockEnvironment env = validEmailValidation()
                .withProperty("notification.outbox.dispatch-enabled", "false");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox dispatch");
    }

    @Test
    void preproductionRejectsProductionCookieScope() {
        MockEnvironment env = validPreproduction()
                .withProperty("auth.cookie.domain", ".viralground.kr");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".staging.viralground.kr");
    }

    @Test
    void preproductionRejectsAdditionalCorsOrigin() {
        MockEnvironment env = validPreproduction()
                .withProperty("cors.allowed-origins",
                        "https://staging.viralground.kr,https://viralground.kr");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("origin 하나만");
    }

    @ParameterizedTest
    @ValueSource(strings = {"staging", "production", "PREPRODUCTION"})
    void preproductionRequiresExactSentryEnvironment(String sentryEnvironment) {
        MockEnvironment env = validPreproduction()
                .withProperty("sentry.environment", sentryEnvironment);

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SENTRY_ENV");
    }

    @Test
    void protectedBackendBindsSentryReleaseToFullCommitSha() {
        MockEnvironment shortSha = validPreproduction()
                .withProperty("app.git-commit-sha", "69c32fd")
                .withProperty("sentry.release", "69c32fd");
        MockEnvironment mismatched = validPreproduction()
                .withProperty("sentry.release", "b".repeat(40));

        assertThatThrownBy(() -> new ProductionSafetyValidator(shortSha).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full lowercase backend GIT_COMMIT_SHA");
        assertThatThrownBy(() -> new ProductionSafetyValidator(mismatched).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SENTRY_RELEASE");
    }

    @Test
    void protectedBackendRejectsUnapprovedOrNonCanonicalSentryIdentityWithoutLeakingKey() {
        String publicKey = "do-not-print-this-public-key";
        MockEnvironment wrongProject = validPreproduction()
                .withProperty("sentry.dsn",
                        "https://" + publicKey + "@o456.ingest.sentry.io/999");
        MockEnvironment queryOverride = validPreproduction()
                .withProperty("sentry.dsn",
                        "https://" + publicKey
                                + "@o456.ingest.sentry.io/2?environment=production");

        assertThatThrownBy(() -> new ProductionSafetyValidator(
                wrongProject).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved host/project identity")
                .hasMessageNotContaining(publicKey);
        assertThatThrownBy(() -> new ProductionSafetyValidator(
                queryOverride).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved host/project identity")
                .hasMessageNotContaining(publicKey);
    }

    @Test
    void protectedBackendRequiresExplicitApprovedSentryIdentity() {
        MockEnvironment missingHost = validPreproduction()
                .withProperty("sentry.approved-host", "");
        MockEnvironment missingProject = validPreproduction()
                .withProperty("sentry.approved-project-id", "");

        assertThatThrownBy(() -> new ProductionSafetyValidator(missingHost).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved backend Sentry");
        assertThatThrownBy(() -> new ProductionSafetyValidator(missingProject).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved backend Sentry");
    }

    @Test
    void preproductionRejectsDifferentAppUrl() {
        MockEnvironment env = validPreproduction()
                .withProperty("app.url", "https://preview.viralground.kr")
                .withProperty("cors.allowed-origins", "https://preview.viralground.kr");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https://staging.viralground.kr");
    }

    @Test
    void preproductionRejectsLiveEmailMode() {
        MockEnvironment env = validEmailValidation()
                .withProperty("email.delivery-mode", "live")
                .withProperty("email.allowed-recipients", "");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL_DELIVERY_MODE=allowlist");
    }

    @Test
    void preproductionDisabledEmailRejectsStaleAllowlist() {
        MockEnvironment env = validPreproduction()
                .withProperty("email.delivery-mode", "disabled")
                .withProperty("email.allowed-recipients", "qa@viralground.kr");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL_ALLOWED_RECIPIENTS");
    }

    @Test
    void preproductionExactCloneRunnerRequiresAndAllowsDisabledEmail() {
        MockEnvironment env = validPreproduction()
                .withProperty("app.migration-runner.enabled", "true")
                .withProperty("instagram.provider", "disabled")
                .withProperty("app.migration-runner.clone-kind", "exact")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://clone.example.test:5432/viralground_release_exact"
                                + "?sslmode=verify-full&currentSchema=public")
                .withProperty("app.migration-runner.allowed-hosts", "clone.example.test")
                .withProperty("app.migration-runner.allowed-databases", "viralground_release_exact")
                .withProperty("app.migration-runner.production-host", "db.viralground.kr")
                .withProperty("app.migration-runner.production-database", "viralground")
                .withProperty("app.migration-runner.database-confirmation",
                        "I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE")
                .withProperty("email.delivery-mode", "disabled")
                .withProperty("email.allowed-recipients", "")
                .withProperty("notification.outbox.enabled", "false")
                .withProperty("app.scheduling.enabled", "false")
                .withProperty("notification.outbox.dispatch-enabled", "false");

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void preproductionMigrationRunnerDoesNotRequireExternalRuntimeCredentials() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("instagram.environment", "preproduction")
                .withProperty("instagram.provider", "disabled")
                .withProperty("app.migration-runner.enabled", "true")
                .withProperty("app.migration-runner.clone-kind", "exact")
                .withProperty("app.migration-runner.allowed-hosts", "clone.example.test")
                .withProperty("app.migration-runner.allowed-databases", "viralground_runner_exact")
                .withProperty("app.migration-runner.production-host", "db.viralground.kr")
                .withProperty("app.migration-runner.production-database", "viralground")
                .withProperty("app.migration-runner.database-confirmation",
                        "I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://clone.example.test:5432/viralground_runner_exact"
                                + "?sslmode=verify-full&currentSchema=public")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("email.delivery-mode", "disabled")
                .withProperty("email.allowed-recipients", "")
                .withProperty("files.storage", "disabled")
                .withProperty("features.uploads.enabled", "false")
                .withProperty("features.payments.enabled", "false")
                .withProperty("features.instagram.enabled", "false")
                .withProperty("payments.gateway", "disabled")
                .withProperty("demo.bootstrap.enabled", "false")
                .withProperty("notification.outbox.enabled", "false")
                .withProperty("notification.outbox.dispatch-enabled", "false")
                .withProperty("app.release-id", "vg-2026-08-22-runner-test")
                .withProperty("app.git-commit-sha", "69c32fd")
                .withProperty("app.build-time", "2026-08-22T01:00:00Z")
                .withProperty("app.scheduling.enabled", "false")
                .withProperty("instagram.sync.enabled", "false")
                .withProperty("instagram.oauth-state.cleanup-enabled", "false")
                .withProperty("instagram.webhook.cleanup-enabled", "false")
                .withProperty("admin.bootstrap.email", "")
                .withProperty("admin.bootstrap.password", "");

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void preproductionExactCompatibilityDoesNotRequireExternalRuntimeCredentials() {
        MockEnvironment env = validExactCompatibility();

        assertThatCode(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void exactCompatibilityRejectsBaselineOnMigrate() {
        MockEnvironment env = validExactCompatibility()
                .withProperty("spring.flyway.baseline-on-migrate", "true");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseline-disabled");
    }

    @Test
    void productionForbidsExactCompatibilityMode() {
        MockEnvironment env = validExactCompatibility()
                .withProperty("app.environment", "production")
                .withProperty("instagram.environment", "production");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preproduction");
    }

    @Test
    void productionForbidsMigrationRunnerMode() {
        MockEnvironment env = validProduction()
                .withProperty("app.migration-runner.enabled", "true")
                .withProperty("instagram.provider", "disabled")
                .withProperty("email.delivery-mode", "disabled");

        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preproduction exact/sanitized clone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "unknown", "replace-me"})
    void protectedEnvironmentRejectsPlaceholderReleaseId(String releaseId) {
        MockEnvironment env = validProduction().withProperty("app.release-id", releaseId);
        assertThatThrownBy(() -> new ProductionSafetyValidator(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELEASE_ID");
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
                .withProperty("instagram.environment", "production")
                .withProperty("instagram.provider", "meta")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://db.viralground.kr:5432/viralground"
                                + "?sslmode=verify-full&currentSchema=public")
                .withProperty("app.url", "https://viralground.kr")
                .withProperty("files.public-base-url", "https://api.viralground.kr")
                .withProperty("cors.allowed-origins", "https://viralground.kr")
                .withProperty("jwt.secret", "production-jwt-secret-longer-than-32-chars")
                .withProperty("files.signing-secret", "independent-file-signing-secret-over-32-chars")
                .withProperty("resend.api-key", "re_live_8Rj2k6m9Q4s7V1x5Z3c0")
                .withProperty("resend.from", "noreply@viralground.kr")
                .withProperty("sentry.dsn", "https://key@o123.ingest.sentry.io/1")
                .withProperty("sentry.environment", "production")
                .withProperty("sentry.release", "a".repeat(40))
                .withProperty("sentry.approved-host", "o123.ingest.sentry.io")
                .withProperty("sentry.approved-project-id", "1")
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
                .withProperty("email.delivery-mode", "live")
                .withProperty("email.allowed-recipients", "")
                .withProperty("files.storage", "s3")
                .withProperty("features.uploads.enabled", "true")
                .withProperty("features.payments.enabled", "false")
                .withProperty("features.instagram.enabled", "false")
                .withProperty("payments.gateway", "disabled")
                .withProperty("rate-limit.backend", "redis")
                .withProperty("spring.data.redis.url",
                        "redis://default:managed-redis-password-2026@redis.railway.internal:6379")
                .withProperty("rate-limit.redis-transport", "railway-private")
                .withProperty("rate-limit.redis-allowed-hosts", "redis.railway.internal")
                .withProperty("rate-limit.redis-environment", "production")
                .withProperty("rate-limit.redis-key-prefix", "viralground:production:rate-limit")
                .withProperty("rate-limit.auth-fail-closed", "true")
                .withProperty("app.release-id", "vg-2026-08-22-rc1")
                .withProperty("app.git-commit-sha", "a".repeat(40))
                .withProperty("app.build-time", "2026-08-22T01:00:00Z")
                .withProperty("app.scheduling.enabled", "true")
                .withProperty("instagram.sync.enabled", "false")
                .withProperty("instagram.oauth-state.cleanup-enabled", "false")
                .withProperty("instagram.webhook.cleanup-enabled", "false")
                .withProperty("notification.outbox.enabled", "true")
                .withProperty("notification.outbox.dispatch-enabled", "true")
                .withProperty("auth.cookie.secure", "true")
                .withProperty("auth.cookie.domain", ".viralground.kr")
                .withProperty("auth.cookie.same-site", "Lax");
    }

    private MockEnvironment validPreproduction() {
        return validProduction()
                .withProperty("app.environment", "preproduction")
                .withProperty("instagram.environment", "preproduction")
                .withProperty("rate-limit.redis-environment", "preproduction")
                .withProperty("rate-limit.redis-key-prefix", "viralground:preproduction:rate-limit")
                .withProperty("app.preproduction-database.clone-kind", "sanitized")
                .withProperty("app.preproduction-database.allowed-hosts", "clone.example.test")
                .withProperty("app.preproduction-database.allowed-databases", "viralground_release_staging")
                .withProperty("app.preproduction-database.production-host", "db.viralground.kr")
                .withProperty("app.preproduction-database.production-database", "viralground")
                .withProperty("app.preproduction-database.database-confirmation",
                        "I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://clone.example.test:5432/viralground_release_staging"
                                + "?sslmode=verify-full&currentSchema=public")
                .withProperty("app.url", "https://staging.viralground.kr")
                .withProperty("cors.allowed-origins", "https://staging.viralground.kr")
                .withProperty("auth.cookie.domain", ".staging.viralground.kr")
                .withProperty("sentry.dsn", "https://stagekey@o456.ingest.sentry.io/2")
                .withProperty("sentry.environment", "preproduction")
                .withProperty("sentry.approved-host", "o456.ingest.sentry.io")
                .withProperty("sentry.approved-project-id", "2")
                .withProperty("resend.from", "qa@preprod.viralground.kr")
                .withProperty("email.delivery-mode", "disabled")
                .withProperty("email.allowed-recipients", "")
                .withProperty("app.scheduling.enabled", "false")
                .withProperty("notification.outbox.dispatch-enabled", "false")
                .withProperty("features.uploads.enabled", "false")
                .withProperty("files.storage", "disabled");
    }

    private MockEnvironment validProvisioning() {
        return validPreproduction()
                .withProperty("app.staging.account-provisioning-enabled", "true")
                .withProperty("app.staging.provisioning-allowed-emails", "qa@viralground.kr")
                .withProperty("email.delivery-mode", "allowlist")
                .withProperty("email.allowed-recipients", "qa@viralground.kr")
                .withProperty("app.scheduling.enabled", "true")
                .withProperty("notification.outbox.dispatch-enabled", "true");
    }

    private MockEnvironment validE2e() {
        return validPreproduction()
                .withProperty("app.staging.e2e-mutation-enabled", "true")
                .withProperty(
                        "app.preproduction-database.e2e-before-evidence-seal-sha256",
                        "c".repeat(64));
    }

    private MockEnvironment validEmailValidation() {
        return validPreproduction()
                .withProperty("app.staging.email-validation-enabled", "true")
                .withProperty(
                        "app.preproduction-database.e2e-before-evidence-seal-sha256",
                        "c".repeat(64))
                .withProperty("email.delivery-mode", "allowlist")
                .withProperty("email.allowed-recipients", "qa@viralground.kr")
                .withProperty("app.staging.email-validation-recipient", "qa@viralground.kr")
                .withProperty("app.scheduling.enabled", "true")
                .withProperty("notification.outbox.dispatch-enabled", "true");
    }

    private MockEnvironment validExactCompatibility() {
        return new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("instagram.environment", "preproduction")
                .withProperty("instagram.provider", "disabled")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://clone.example.test:5432/viralground_release_exact"
                                + "?sslmode=verify-full&currentSchema=public")
                .withProperty("app.preproduction-database.clone-kind", "exact")
                .withProperty("app.preproduction-database.allowed-hosts", "clone.example.test")
                .withProperty("app.preproduction-database.allowed-databases", "viralground_release_exact")
                .withProperty("app.preproduction-database.production-host", "db.viralground.kr")
                .withProperty("app.preproduction-database.production-database", "viralground")
                .withProperty("app.preproduction-database.database-confirmation",
                        "I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE")
                .withProperty("app.exact-compatibility.enabled", "true")
                .withProperty("app.migration-runner.enabled", "false")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.baseline-on-migrate", "false")
                .withProperty("email.delivery-mode", "disabled")
                .withProperty("email.allowed-recipients", "")
                .withProperty("files.storage", "disabled")
                .withProperty("features.uploads.enabled", "false")
                .withProperty("features.payments.enabled", "false")
                .withProperty("features.instagram.enabled", "false")
                .withProperty("payments.gateway", "disabled")
                .withProperty("demo.bootstrap.enabled", "false")
                .withProperty("notification.outbox.enabled", "false")
                .withProperty("notification.outbox.dispatch-enabled", "false")
                .withProperty("app.release-id", "vg-2026-08-22-exact-compatibility")
                .withProperty("app.git-commit-sha", "69c32fd")
                .withProperty("app.build-time", "2026-08-22T01:00:00Z")
                .withProperty("app.scheduling.enabled", "false")
                .withProperty("instagram.sync.enabled", "false")
                .withProperty("instagram.oauth-state.cleanup-enabled", "false")
                .withProperty("instagram.webhook.cleanup-enabled", "false")
                .withProperty("admin.bootstrap.email", "")
                .withProperty("admin.bootstrap.password", "");
    }
}

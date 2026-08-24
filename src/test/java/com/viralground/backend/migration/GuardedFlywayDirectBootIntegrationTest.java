package com.viralground.backend.migration;

import com.viralground.backend.MarketingBackendApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves an unsafe protected JAR-style boot fails before Flyway creates history. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class GuardedFlywayDirectBootIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine@sha256:5660c2cbfea50c7a9127d17dc4e48543eedd3d7a41a595a2dfa572471e37e64c")
            .withDatabaseName("viralground_guard_exact")
            .withUsername("viralground_guard")
            .withPassword("guard-test-password");

    @Test
    void protectedLoopbackTargetFailsBeforeFlywayHistoryExists() throws Exception {
        String actualHost = POSTGRES.getHost();
        String[] arguments = {
                "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.flyway.enabled=true",
                "--spring.flyway.baseline-version=1",
                "--spring.flyway.baseline-on-migrate=true",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--app.environment=preproduction",
                "--app.migration-runner.enabled=true",
                "--app.migration-runner.clone-kind=exact",
                "--app.migration-runner.sentinel-id=integration-guard-sentinel",
                "--app.migration-runner.source-snapshot-id=integration-snapshot-20260822",
                "--app.migration-runner.allowed-hosts=not-" + actualHost,
                "--app.migration-runner.allowed-databases=viralground_guard_exact",
                "--app.migration-runner.production-host=production-db.internal",
                "--app.migration-runner.production-database=viralground_production",
                "--app.migration-runner.database-confirmation="
                        + "I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE",
                "--app.migration-runner.migration-confirmation="
                        + "BASELINE_V1_ON_DISPOSABLE_EXACT_CLONE_ONCE",
                "--app.url=https://staging.viralground.kr",
                "--cors.allowed-origins=https://staging.viralground.kr",
                "--sentry.dsn=https://key@o123.ingest.sentry.io/1",
                "--jwt.secret=integration-guard-secret-longer-than-32-characters",
                "--resend.api-key=re_live_8Rj2k6m9Q4s7V1x5Z3c0",
                "--resend.from=qa@preprod.viralground.kr",
                "--email.mock=false",
                "--email.delivery-mode=disabled",
                "--email.allowed-recipients=",
                "--files.storage=disabled",
                "--features.uploads.enabled=false",
                "--features.payments.enabled=false",
                "--features.instagram.enabled=false",
                "--payments.gateway=disabled",
                "--instagram.provider=mock",
                "--instagram.environment=preproduction",
                "--rate-limit.backend=redis",
                "--spring.data.redis.url=rediss://guard:secret@redis.internal:6379",
                "--rate-limit.auth-fail-closed=true",
                "--legal.documents.terms-version=terms-2026-08-22",
                "--legal.documents.privacy-version=privacy-2026-08-22",
                "--legal.documents.age14-version=age14-2026-08-22",
                "--legal.documents.creator-third-party-version=creator-2026-08-22",
                "--legal.documents.marketing-version=marketing-2026-08-22",
                "--legal.privacy-officer.name=Privacy Officer",
                "--legal.privacy-officer.contact=privacy@viralground.kr",
                "--app.release-id=vg-2026-08-22-guard-test",
                "--app.git-commit-sha=69c32fd",
                "--app.build-time=2026-08-22T01:00:00Z",
                "--app.scheduling.enabled=false",
                "--instagram.sync.enabled=false",
                "--instagram.oauth-state.cleanup-enabled=false",
                "--instagram.webhook.cleanup-enabled=false",
                "--notification.outbox.enabled=false",
                "--notification.outbox.dispatch-enabled=false",
                "--auth.cookie.secure=true",
                "--auth.cookie.domain=.staging.viralground.kr",
                "--auth.cookie.same-site=Lax",
                "--admin.bootstrap.email=",
                "--admin.bootstrap.password="
        };

        assertThatThrownBy(() -> new SpringApplicationBuilder(
                MarketingBackendApplication.class).run(arguments))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("protected runtime refuses a loopback PostgreSQL target");

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(
                     "SELECT to_regclass('public.flyway_schema_history') IS NULL");
             var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1))
                    .as("invalid direct runner boot must not create Flyway history")
                    .isTrue();
        }
    }
}

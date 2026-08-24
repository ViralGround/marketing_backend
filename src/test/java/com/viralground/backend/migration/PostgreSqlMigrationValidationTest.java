package com.viralground.backend.migration;

import com.viralground.backend.MarketingBackendApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 빈 실제 PostgreSQL에 모든 Flyway migration을 순서대로 적용한 뒤
 * Hibernate ddl-auto=validate로 전체 JPA mapping을 검증한다.
 *
 * <p>로컬 Docker가 없으면 Testcontainers가 명시적으로 skip한다. CI workflow는
 * Docker를 사전 검사하고 XML에서 skip=0을 확인하므로 조용한 미실행을 허용하지 않는다.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlMigrationValidationTest {

    private static final String SAFE_TEST_SECRET =
            "migration-test-secret-not-for-production-32-bytes";
    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V([0-9][0-9._]*)__.+\\.sql$");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine@sha256:5660c2cbfea50c7a9127d17dc4e48543eedd3d7a41a595a2dfa572471e37e64c")
            .withDatabaseName("viralground_migration_test")
            .withUsername("viralground_test")
            .withPassword("viralground_test_password");

    @Test
    void appliesEveryMigrationAndPassesHibernateValidateOnEmptyPostgres() {
        // production validator를 mock/disable하지 않는다. app.environment=test와 로컬 전용
        // 안전 설정을 명시하여 실제 startup chain 전체가 실행되도록 한다.
        // Command-line property precedence is intentional: an existing local .env or CI secret must
        // never redirect this destructive, fresh-database test away from its disposable container.
        String[] safeTestArguments = {
                "--spring.profiles.active=migration-test",
                "--spring.main.banner-mode=off",
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.flyway.enabled=true",
                "--spring.flyway.baseline-on-migrate=false",
                "--spring.flyway.validate-on-migrate=true",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                "--app.environment=test",
                "--app.url=http://localhost:3000",
                "--cors.allowed-origins=http://localhost:3000",
                "--jwt.secret=" + SAFE_TEST_SECRET,
                "--files.storage=local",
                "--files.local.directory=" + temporaryUploadDirectory(),
                "--files.public-base-url=http://localhost:8080",
                "--files.signing-secret=" + SAFE_TEST_SECRET + "-files",
                "--email.mock=true",
                "--resend.api-key=",
                "--instagram.environment=test",
                "--instagram.provider=mock",
                "--payments.gateway=disabled",
                "--sentry.dsn=",
                "--admin.bootstrap.email=",
                "--admin.bootstrap.password="
        };

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                MarketingBackendApplication.class).run(safeTestArguments)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));

            List<String> successfulVersions = jdbc.queryForList("""
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = TRUE AND type = 'SQL'
                    ORDER BY installed_rank
                    """, String.class);
            assertThat(successfulVersions)
                    .as("Every versioned migration must be applied on a fresh database")
                    .containsExactlyElementsOf(expectedMigrationVersions());

            Integer failedMigrations = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE success = FALSE
                    """, Integer.class);
            assertThat(failedMigrations).isZero();

            Integer mappedTableCount = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN (
                        'members', 'creator_profiles', 'company_profiles', 'campaigns',
                        'campaign_applications', 'application_submissions',
                        'email_verification_codes', 'escrow_transactions', 'reviews',
                        'submission_metrics', 'creator_instagram_connections',
                        'reel_metric_snapshots', 'contact_requests', 'audit_logs',
                        'refresh_tokens', 'upload_records', 'payment_ledger_entries',
                        'payment_webhook_events', 'instagram_oauth_states',
                        'instagram_webhook_deliveries', 'member_consent_evidence',
                        'marketing_consent_events', 'notification_outbox'
                      )
                    """, Integer.class);
            assertThat(mappedTableCount).isEqualTo(23);

            assertConsentEvidenceIsMinimalAndAppendOnly(jdbc);
            assertAuditLogIsAppendOnly(jdbc);
            assertContactPrivacyEvidenceIsImmutable(jdbc);
            assertWithdrawalLifecycleConstraint(jdbc);
            assertCreatorDirectoryDefaultsPrivate(jdbc);
            assertCampaignBudgetConstraints(jdbc);
            assertMarketingConsentEvidenceIsAppendOnly(jdbc);
            assertCompanyHomepageConstraint(jdbc);
            assertNonfinancialCompletionMarkerSchema(jdbc);
        }
    }

    private void assertNonfinancialCompletionMarkerSchema(JdbcTemplate jdbc) {
        Integer columnCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'campaign_applications'
                  AND column_name = 'content_approved_at'
                  AND is_nullable = 'YES'
                """, Integer.class);
        assertThat(columnCount).isOne();

        String definition = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'campaign_applications'::regclass
                  AND conname = 'ck_nonfinancial_completion'
                """, String.class);
        assertThat(definition)
                .contains("content_approved_at", "SETTLED", "reward_paid_amount", "settled_at")
                .doesNotContain("COMPLETED");
    }

    private void assertCompanyHomepageConstraint(JdbcTemplate jdbc) {
        Integer companyId = jdbc.queryForObject("""
                INSERT INTO members (
                    email, password, name, role, status, email_verified, created_at, updated_at
                ) VALUES (
                    'homepage-check@example.test', 'not-a-real-hash', 'Homepage check company',
                    'COMPANY', 'APPROVED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, Integer.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO company_profiles (
                    member_id, company_name, business_number, representative_name,
                    contact_name, contact_phone, homepage, created_at, updated_at
                ) VALUES (
                    ?, 'Homepage check', '1234567890', '대표', '담당자', '010-0000-0000',
                    'javascript:alert(1)', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """, companyId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_company_homepage_https");
    }

    private void assertMarketingConsentEvidenceIsAppendOnly(JdbcTemplate jdbc) {
        Integer memberId = jdbc.queryForObject("""
                INSERT INTO members (
                    email, password, name, role, status, email_verified, created_at, updated_at
                ) VALUES (
                    'marketing-evidence@example.test', 'not-a-real-hash', 'Marketing evidence',
                    'CREATOR', 'APPROVED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, Integer.class);
        Long eventId = jdbc.queryForObject("""
                INSERT INTO marketing_consent_events (
                    member_id, action, document_version, occurred_at
                ) VALUES (?, 'OPT_IN', 'marketing-final-v1', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, memberId);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE marketing_consent_events SET action = 'OPT_OUT' WHERE id = ?
                """, eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM marketing_consent_events WHERE id = ?", eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private void assertCampaignBudgetConstraints(JdbcTemplate jdbc) {
        Integer companyId = jdbc.queryForObject("""
                INSERT INTO members (
                    email, password, name, role, status, email_verified, created_at, updated_at
                ) VALUES (
                    'budget-check-company@example.test', 'not-a-real-hash', 'Budget check company',
                    'COMPANY', 'APPROVED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, Integer.class);

        assertCampaignConstraint(jdbc, companyId, 0, 1, 0,
                "ck_campaign_reward_amount");
        assertCampaignConstraint(jdbc, companyId, 1, 10001, 10001,
                "ck_campaign_max_participants");
        assertCampaignConstraint(jdbc, companyId, 10000, 2, 1,
                "ck_campaign_total_budget");
    }

    private void assertCampaignConstraint(JdbcTemplate jdbc, Integer companyId,
                                          int rewardAmount, int maxParticipants, int totalBudget,
                                          String constraintName) {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO campaigns (
                    title, description, brand_name, reward_amount, total_budget,
                    escrow_status, max_participants, status, created_by_id, created_at, updated_at
                ) VALUES (
                    'Invalid budget fixture', 'Must be rejected by V11', 'ViralGround',
                    ?, ?, 'NONE', ?, 'DRAFT', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """, rewardAmount, totalBudget, maxParticipants, companyId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(constraintName);
    }

    private void assertCreatorDirectoryDefaultsPrivate(JdbcTemplate jdbc) {
        Integer memberId = jdbc.queryForObject("""
                INSERT INTO members (
                    email, password, name, role, status, email_verified, created_at, updated_at
                ) VALUES (
                    'private-profile@example.test', 'not-a-real-hash', 'Private creator',
                    'CREATOR', 'APPROVED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, Integer.class);
        Integer profileId = jdbc.queryForObject("""
                INSERT INTO creator_profiles (
                    member_id, can_edit, editing_skill, face_exposure, created_at, updated_at
                ) VALUES (?, FALSE, 'LOW', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Integer.class, memberId);
        Boolean defaultOptIn = jdbc.queryForObject(
                "SELECT public_profile_opt_in FROM creator_profiles WHERE id = ?",
                Boolean.class, profileId);
        assertThat(defaultOptIn).isFalse();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE creator_profiles
                SET public_profile_opt_in = TRUE
                WHERE id = ?
                """, profileId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_creator_public_profile_consent");
    }

    private void assertWithdrawalLifecycleConstraint(JdbcTemplate jdbc) {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO members (
                    email, password, name, role, status, email_verified, created_at, updated_at
                ) VALUES (
                    'invalid-withdrawal@example.test', 'not-a-real-hash', 'Invalid withdrawal',
                    'CREATOR', 'WITHDRAWN', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_members_withdrawal_timestamp");

        Integer companyId = jdbc.queryForObject("""
                INSERT INTO members (
                    email, password, name, role, status, email_verified, created_at, updated_at
                ) VALUES (
                    'status-check-company@example.test', 'not-a-real-hash', 'Status check company',
                    'COMPANY', 'APPROVED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, Integer.class);
        Integer creatorId = jdbc.queryForObject("""
                INSERT INTO members (
                    email, password, name, role, status, email_verified, created_at, updated_at
                ) VALUES (
                    'status-check-creator@example.test', 'not-a-real-hash', 'Status check creator',
                    'CREATOR', 'APPROVED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, Integer.class);
        Integer campaignId = jdbc.queryForObject("""
                INSERT INTO campaigns (
                    title, description, brand_name, reward_amount, total_budget,
                    escrow_status, max_participants, status, created_by_id, created_at, updated_at
                ) VALUES (
                    'Status constraint campaign', 'Migration constraint fixture', 'ViralGround',
                    10000, 10000, 'NONE', 1, 'DRAFT', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, Integer.class, companyId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO campaign_applications (
                    campaign_id, creator_id, status, resubmission_count, applied_at, updated_at
                ) VALUES (?, ?, 'UNKNOWN', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, campaignId, creatorId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_campaign_applications_status");
    }

    private void assertContactPrivacyEvidenceIsImmutable(JdbcTemplate jdbc) {
        Integer contactId = jdbc.queryForObject("""
                INSERT INTO contact_requests (
                    email, brand_name, privacy_consent_version, privacy_consented_at, created_at
                ) VALUES (
                    'contact-consent@example.test', 'Consent test', 'privacy-test-final-v1',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """, Integer.class);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE contact_requests
                SET privacy_consent_version = 'mutated'
                WHERE id = ?
                """, contactId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    private void assertAuditLogIsAppendOnly(JdbcTemplate jdbc) {
        Long auditId = jdbc.queryForObject("""
                INSERT INTO audit_logs (
                    request_id, actor_id, actor_role, action, resource_type,
                    resource_id, outcome, reason, created_at
                ) VALUES (
                    'migration-test-request', 1, 'ADMIN', 'CAMPAIGN_STATE_CHANGED',
                    'campaign', '1', 'SUCCESS', 'migration verification', CURRENT_TIMESTAMP
                )
                RETURNING id
                """, Long.class);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_logs SET outcome = 'MUTATED' WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM audit_logs WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private void assertConsentEvidenceIsMinimalAndAppendOnly(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'member_consent_evidence'
                ORDER BY ordinal_position
                """, String.class);
        assertThat(columns).containsExactly(
                "id", "member_id", "consent_type", "document_version", "agreed_at");

        Integer memberId = jdbc.queryForObject("""
                INSERT INTO members (
                    email, password, name, role, status, email_verified, created_at, updated_at
                ) VALUES (?, ?, ?, 'CREATOR', 'PENDING', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Integer.class, "consent-trigger@example.test", "not-a-real-hash", "동의 트리거 테스트");

        Long evidenceId = jdbc.queryForObject("""
                INSERT INTO member_consent_evidence (
                    member_id, consent_type, document_version, agreed_at
                ) VALUES (?, 'TERMS_OF_SERVICE', 'terms-test-final-v1', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, memberId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO member_consent_evidence (
                    member_id, consent_type, document_version, agreed_at
                ) VALUES (?, 'TERMS_OF_SERVICE', 'terms-test-final-v1', CURRENT_TIMESTAMP)
                """, memberId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_member_consent_type_version");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE member_consent_evidence
                SET document_version = 'mutated'
                WHERE id = ?
                """, evidenceId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM member_consent_evidence WHERE id = ?", evidenceId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private static List<String> expectedMigrationVersions() {
        Path migrationDirectory = Path.of("src", "main", "resources", "db", "migration");
        try (var files = Files.list(migrationDirectory)) {
            List<String> versions = new ArrayList<>();
            files.map(path -> path.getFileName().toString())
                    .forEach(name -> {
                        Matcher matcher = VERSIONED_MIGRATION.matcher(name);
                        if (matcher.matches()) versions.add(matcher.group(1).replace('_', '.'));
                    });
            versions.sort(Comparator.comparing(
                    org.flywaydb.core.api.MigrationVersion::fromVersion));
            assertThat(versions).as("Versioned migration files must exist").isNotEmpty();
            return List.copyOf(versions);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to discover Flyway migration versions", e);
        }
    }

    private static String temporaryUploadDirectory() {
        try {
            Path directory = Files.createTempDirectory("viralground-migration-test-");
            directory.toFile().deleteOnExit();
            return directory.toAbsolutePath().toString().replace('\\', '/');
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create migration-test upload directory", e);
        }
    }
}

package com.viralground.backend.migration;

import com.viralground.backend.MarketingBackendApplication;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the one-time legacy production path: an existing V1-shaped schema is
 * baselined at version 1, then only V2+ is applied. Each negative fixture proves
 * that a known unsafe legacy state stops at the intended migration.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class LegacyBaselineMigrationValidationTest {

    private static final String SAFE_TEST_SECRET =
            "legacy-migration-test-secret-not-for-production";
    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V([0-9][0-9._]*)__.+\\.sql$");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine@sha256:5660c2cbfea50c7a9127d17dc4e48543eedd3d7a41a595a2dfa572471e37e64c")
            .withDatabaseName("viralground_legacy_migration_test")
            .withUsername("viralground_test")
            .withPassword("viralground_test_password");

    @Test
    void baselinesExistingV1SchemaThenAppliesEveryLaterMigrationAndValidatesJpa() throws Exception {
        String schema = "legacy_success";
        installV1ShapedLegacySchema(schema);

        migrateLegacy(schema, null);

        JdbcTemplate jdbc = jdbc(schema);
        List<String> installed = jdbc.queryForList("""
                SELECT version || ':' || type
                FROM flyway_schema_history
                WHERE success = TRUE
                ORDER BY installed_rank
                """, String.class);

        assertThat(installed.getFirst()).isEqualTo("1:BASELINE");
        assertThat(installed.stream().filter(value -> value.endsWith(":SQL")).toList())
                .containsExactlyElementsOf(expectedSqlVersionsAfterBaseline());

        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(
                MarketingBackendApplication.class).run(safeValidateArguments(schema))) {
            // Successful startup is the assertion: Hibernate ddl-auto=validate checks every mapping.
        }
    }

    @Test
    void v3RejectsDuplicateLegacyDeposits() throws Exception {
        String schema = "legacy_bad_v3";
        installV1ShapedLegacySchema(schema);
        migrateLegacy(schema, "2");
        JdbcTemplate jdbc = jdbc(schema);
        Integer companyId = insertMember(jdbc, "v3-company@example.test", "COMPANY", "APPROVED");
        Integer campaignId = insertCampaign(jdbc, companyId, 10_000, 1, 10_000);
        jdbc.update("""
                INSERT INTO escrow_transactions (campaign_id, type, amount, memo, created_at)
                VALUES (?, 'DEPOSIT', 10000, 'first', CURRENT_TIMESTAMP),
                       (?, 'DEPOSIT', 10000, 'duplicate', CURRENT_TIMESTAMP + INTERVAL '1 second')
                """, campaignId, campaignId);

        assertMigrationRejected(schema, "3", "uq_escrow_single_deposit");
    }

    @Test
    void v3RejectsNonpositiveLegacyAmount() throws Exception {
        String schema = "legacy_bad_v3_amount";
        installV1ShapedLegacySchema(schema);
        migrateLegacy(schema, "2");
        JdbcTemplate jdbc = jdbc(schema);
        Integer companyId = insertMember(jdbc, "v3-amount@example.test", "COMPANY", "APPROVED");
        Integer campaignId = insertCampaign(jdbc, companyId, 10_000, 1, 10_000);
        jdbc.update("""
                INSERT INTO escrow_transactions (campaign_id, type, amount, memo, created_at)
                VALUES (?, 'DEPOSIT', 0, 'invalid', CURRENT_TIMESTAMP)
                """, campaignId);

        assertMigrationRejected(schema, "3", "nonpositive amount");
    }

    @Test
    void v3RejectsUnknownLegacyTransactionType() throws Exception {
        String schema = "legacy_bad_v3_type";
        installV1ShapedLegacySchema(schema);
        migrateLegacy(schema, "2");
        JdbcTemplate jdbc = jdbc(schema);
        Integer companyId = insertMember(jdbc, "v3-type@example.test", "COMPANY", "APPROVED");
        Integer campaignId = insertCampaign(jdbc, companyId, 10_000, 1, 10_000);
        jdbc.update("""
                INSERT INTO escrow_transactions (campaign_id, type, amount, memo, created_at)
                VALUES (?, 'REVERSAL', 10000, 'invalid', CURRENT_TIMESTAMP)
                """, campaignId);

        assertMigrationRejected(schema, "3", "unknown transaction type");
    }

    @Test
    void v3RejectsNegativeLegacyRunningBalance() throws Exception {
        String schema = "legacy_bad_v3_balance";
        installV1ShapedLegacySchema(schema);
        migrateLegacy(schema, "2");
        JdbcTemplate jdbc = jdbc(schema);
        Integer companyId = insertMember(jdbc, "v3-balance@example.test", "COMPANY", "APPROVED");
        Integer campaignId = insertCampaign(jdbc, companyId, 10_000, 1, 10_000);
        jdbc.update("""
                INSERT INTO escrow_transactions (campaign_id, type, amount, memo, created_at)
                VALUES (?, 'DEPOSIT', 100, 'deposit', CURRENT_TIMESTAMP),
                       (?, 'REFUND', 200, 'over-refund', CURRENT_TIMESTAMP + INTERVAL '1 second')
                """, campaignId, campaignId);

        assertMigrationRejected(schema, "3", "negative running balance");
    }

    @Test
    void v4RejectsDuplicateMetaProviderAccounts() throws Exception {
        String schema = "legacy_bad_v4";
        installV1ShapedLegacySchema(schema);
        migrateLegacy(schema, "3");
        JdbcTemplate jdbc = jdbc(schema);
        Integer creatorOne = insertMember(jdbc, "v4-one@example.test", "CREATOR", "APPROVED");
        Integer creatorTwo = insertMember(jdbc, "v4-two@example.test", "CREATOR", "APPROVED");
        jdbc.update("""
                INSERT INTO creator_instagram_connections (
                    creator_id, provider, provider_account_id, status, created_at, updated_at
                ) VALUES
                    (?, 'META', 'duplicate-account', 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (?, 'META', 'duplicate-account', 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, creatorOne, creatorTwo);

        assertMigrationRejected(schema, "4", "uk_creator_instagram_provider_account");
    }

    @Test
    void v9RejectsUnknownLegacyMemberStatus() throws Exception {
        String schema = "legacy_bad_v9";
        installV1ShapedLegacySchema(schema);
        migrateLegacy(schema, "8");
        insertMember(jdbc(schema), "v9-member@example.test", "CREATOR", "LEGACY_UNKNOWN");

        assertMigrationRejected(schema, "9", "ck_members_status");
    }

    @Test
    void v9RejectsLegacyWithdrawnMemberWithoutTimestamp() throws Exception {
        String schema = "legacy_bad_v9_withdrawn";
        installV1ShapedLegacySchema(schema);
        migrateLegacy(schema, "8");
        insertMember(jdbc(schema), "v9-withdrawn@example.test", "CREATOR", "WITHDRAWN");

        assertMigrationRejected(schema, "9", "ck_members_withdrawal_timestamp");
    }

    @Test
    void v11RejectsInvalidLegacyCampaignBudget() throws Exception {
        String schema = "legacy_bad_v11";
        installV1ShapedLegacySchema(schema);
        migrateLegacy(schema, "10");
        JdbcTemplate jdbc = jdbc(schema);
        Integer companyId = insertMember(jdbc, "v11-company@example.test", "COMPANY", "APPROVED");
        insertCampaign(jdbc, companyId, 10_000, 2, 10_000);

        assertMigrationRejected(schema, "11", "campaign budget audit failed before V11 constraints");
    }

    private void installV1ShapedLegacySchema(String schema) throws Exception {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
        }
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl(schema), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__baseline_schema.sql"));
        }
    }

    private void migrateLegacy(String schema, String targetVersion) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl(schema), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .validateOnMigrate(true)
                .cleanDisabled(true);
        if (targetVersion != null) {
            configuration.target(MigrationVersion.fromVersion(targetVersion));
        }
        configuration.load().migrate();
    }

    private void assertMigrationRejected(String schema, String targetVersion, String expectedFailure) {
        assertThatThrownBy(() -> Flyway.configure()
                .dataSource(jdbcUrl(schema), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .target(MigrationVersion.fromVersion(targetVersion))
                .load()
                .migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining(expectedFailure);
    }

    private JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(new DriverManagerDataSource(
                jdbcUrl(schema), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private String jdbcUrl(String schema) {
        String delimiter = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return POSTGRES.getJdbcUrl() + delimiter + "currentSchema=" + schema;
    }

    private Integer insertMember(JdbcTemplate jdbc, String email, String role, String status) {
        return jdbc.queryForObject("""
                INSERT INTO members (
                    email, password, name, role, status, email_verified, created_at, updated_at
                ) VALUES (?, 'not-a-real-hash', 'Legacy fixture', ?, ?, TRUE,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Integer.class, email, role, status);
    }

    private Integer insertCampaign(JdbcTemplate jdbc, Integer companyId, int reward,
                                   int participants, int totalBudget) {
        return jdbc.queryForObject("""
                INSERT INTO campaigns (
                    title, description, brand_name, reward_amount, total_budget,
                    escrow_status, max_participants, status, created_by_id, created_at, updated_at
                ) VALUES (
                    'Legacy fixture', 'Legacy fixture', 'Legacy fixture', ?, ?,
                    'NONE', ?, 'DRAFT', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, Integer.class, reward, totalBudget, participants, companyId);
    }

    private String[] safeValidateArguments(String schema) throws Exception {
        Path uploadDirectory = Files.createTempDirectory("viralground-legacy-validation-");
        uploadDirectory.toFile().deleteOnExit();
        return new String[] {
                "--spring.profiles.active=migration-test",
                "--spring.main.banner-mode=off",
                "--server.port=0",
                "--spring.datasource.url=" + jdbcUrl(schema),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                "--app.environment=test",
                "--app.scheduling.enabled=false",
                "--features.payments.enabled=false",
                "--features.instagram.enabled=false",
                "--features.uploads.enabled=false",
                "--notification.outbox.enabled=false",
                "--notification.outbox.dispatch-enabled=false",
                "--app.url=http://localhost:3000",
                "--cors.allowed-origins=http://localhost:3000",
                "--jwt.secret=" + SAFE_TEST_SECRET,
                "--files.storage=disabled",
                "--files.local.directory=" + uploadDirectory.toAbsolutePath().toString().replace('\\', '/'),
                "--files.public-base-url=http://localhost:8080",
                "--files.signing-secret=" + SAFE_TEST_SECRET + "-files",
                "--email.mock=true",
                "--email.delivery-mode=disabled",
                "--resend.api-key=",
                "--instagram.environment=test",
                "--instagram.provider=mock",
                "--payments.gateway=disabled",
                "--sentry.dsn=",
                "--admin.bootstrap.email=",
                "--admin.bootstrap.password="
        };
    }

    private List<String> expectedSqlVersionsAfterBaseline() throws Exception {
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            return files.map(path -> path.getFileName().toString())
                    .map(VERSIONED_MIGRATION::matcher)
                    .filter(Matcher::matches)
                    .map(matcher -> matcher.group(1).replace('_', '.') + ":SQL")
                    .filter(version -> !version.equals("1:SQL"))
                    .sorted(Comparator.comparing(value ->
                            MigrationVersion.fromVersion(value.substring(0, value.indexOf(':')))))
                    .toList();
        }
    }
}

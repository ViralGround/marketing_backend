package com.viralground.backend.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardedFlywayMigrationConfigurationTest {
    private static final String EVIDENCE_SEAL = "a".repeat(64);
    private static final String E2E_BEFORE_SEAL = "c".repeat(64);
    private static final String SOURCE_SNAPSHOT = "provider-snapshot-20260822";

    private final GuardedFlywayMigrationConfiguration configuration =
            new GuardedFlywayMigrationConfiguration();
    private final DataSource dataSource = mock(DataSource.class);
    private final ProductionSafetyValidator safetyValidator = mock(ProductionSafetyValidator.class);
    private final RuntimeSafetyState runtimeSafetyState = mock(RuntimeSafetyState.class);

    @Test
    void productionNormalRuntimeValidatesButNeverMigrates() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production")
                .withProperty("app.migration-runner.enabled", "false");
        Flyway flyway = flywayWithPending(new MigrationInfo[0]);

        configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway);

        verify(flyway).validate();
        verify(flyway, never()).migrate();
    }

    @Test
    void normalPreproductionRuntimeRejectsExactCloneBeforeDatabaseAccess() throws Exception {
        MockEnvironment environment = preproductionRuntime("exact");
        Flyway flyway = flywayWithPending(new MigrationInfo[0]);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sanitized clone");
        verify(dataSource, never()).getConnection();
        verify(flyway, never()).migrate();
    }

    @Test
    void normalPreproductionRuntimeRequiresCompletedReleaseBoundSentinel() throws Exception {
        Connection connection = mockCompletedRuntimeConnection(false);
        when(dataSource.getConnection()).thenReturn(connection);
        MockEnvironment environment = preproductionRuntime("sanitized");
        Flyway flyway = flywayWithPending(new MigrationInfo[0]);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not migration/evidence complete");
        verify(flyway, never()).migrate();
    }

    @Test
    void completedSanitizedRuntimeValidatesButNeverMigrates() throws Exception {
        Connection connection = mockCompletedRuntimeConnection(true);
        when(dataSource.getConnection()).thenReturn(connection);
        MockEnvironment environment = preproductionRuntime("sanitized");
        Flyway flyway = flywayWithPending(new MigrationInfo[0]);

        configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway);

        verify(connection).setReadOnly(true);
        verify(runtimeSafetyState).recordCompletedClone(
                "sanitized",
                "release-sentinel",
                SOURCE_SNAPSHOT,
                "release-guard-test",
                "viralground_release_staging",
                EVIDENCE_SEAL,
                E2E_BEFORE_SEAL);
        verify(flyway).validate();
        verify(flyway, never()).migrate();
    }

    @Test
    void protectedNormalRuntimeRejectsPendingWithoutMigrating() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production")
                .withProperty("app.migration-runner.enabled", "false");
        Flyway flyway = flywayWithPending(new MigrationInfo[]{mock(MigrationInfo.class)});

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");
        verify(flyway, never()).migrate();
    }

    @Test
    void developmentRuntimeKeepsAutomaticMigrationBehavior() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "development")
                .withProperty("app.migration-runner.enabled", "false")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:flyway-local-test");
        Flyway flyway = mock(Flyway.class);

        configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway);

        verify(flyway).migrate();
    }

    @Test
    void developmentLabelCannotAutoMigrateRemoteDatabase() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "development")
                .withProperty("app.migration-runner.enabled", "false")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://remote-db.internal:5432/viralground?sslmode=require");
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");
        verify(flyway, never()).migrate();
    }

    @ParameterizedTest
    @ValueSource(strings = {"production ", "pre-production", "prod", "staging"})
    void ambiguousOrAliasedAppEnvironmentFailsClosed(String appEnvironment) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", appEnvironment)
                .withProperty("spring.datasource.url", "jdbc:h2:mem:must-not-start");
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENV");
        verify(flyway, never()).migrate();
    }

    @Test
    void protectedProfileCannotWeakenTheExplicitAppEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:must-not-start");
        environment.setActiveProfiles("preproduction");
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly match");
        verify(flyway, never()).migrate();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://clone.example.test:5432/viralground_release_staging"
                    + "?sslmode=verify-full&sslmode=disable&currentSchema=public",
            "jdbc:postgresql://clone.example.test:5432/viralground_release_staging"
                    + "?sslmode=verify-full&currentSchema=public&CURRENTSCHEMA=other"
    })
    void preproductionRuntimeRejectsDuplicateSecurityQueryParameters(String jdbcUrl) throws Exception {
        MockEnvironment environment = preproductionRuntime("sanitized")
                .withProperty("spring.datasource.url", jdbcUrl);
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
        verify(dataSource, never()).getConnection();
        verify(flyway, never()).migrate();
    }

    @Test
    void remotePreproductionRuntimeRequiresVerifyFull() throws Exception {
        MockEnvironment environment = preproductionRuntime("sanitized")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://clone.example.test:5432/viralground_release_staging"
                                + "?sslmode=require&currentSchema=public");
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verify-full");
        verify(dataSource, never()).getConnection();
        verify(flyway, never()).migrate();
    }

    @Test
    void normalPreproductionRuntimeRequiresExpectedEvidenceSealBeforeDatabaseAccess()
            throws Exception {
        MockEnvironment environment = preproductionRuntime("sanitized")
                .withProperty("app.preproduction-database.evidence-seal-sha256", "");
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence-seal-sha256");
        verify(dataSource, never()).getConnection();
        verify(flyway, never()).migrate();
    }

    @Test
    void normalPreproductionRuntimeRequiresProtectedSourceSnapshotBeforeDatabaseAccess()
            throws Exception {
        MockEnvironment environment = preproductionRuntime("sanitized")
                .withProperty("app.preproduction-database.source-snapshot-id", "");
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source-snapshot-id");
        verify(dataSource, never()).getConnection();
        verify(flyway, never()).migrate();
    }

    @Test
    void normalPreproductionRuntimeRejectsMismatchedLiveEvidenceSeal() throws Exception {
        Connection connection = mockCompletedRuntimeConnection(true, "b".repeat(64));
        when(dataSource.getConnection()).thenReturn(connection);
        MockEnvironment environment = preproductionRuntime("sanitized");
        Flyway flyway = flywayWithPending(new MigrationInfo[0]);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence seal");
        verify(runtimeSafetyState, never()).recordCompletedClone(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(flyway, never()).migrate();
    }

    @Test
    void e2eMutationRuntimeRejectsMismatchedBeforeEvidenceSeal() throws Exception {
        Connection connection = mockCompletedRuntimeConnection(
                true, EVIDENCE_SEAL, "d".repeat(64));
        when(dataSource.getConnection()).thenReturn(connection);
        MockEnvironment environment = preproductionRuntime("sanitized");
        Flyway flyway = flywayWithPending(new MigrationInfo[0]);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("E2E-before evidence seal");
        verify(flyway, never()).migrate();
    }

    @Test
    void explicitProvisioningRuntimeRequiresOnlyTheCompletedMigrationSeal() throws Exception {
        Connection connection = mockCompletedRuntimeConnection(true);
        when(dataSource.getConnection()).thenReturn(connection);
        MockEnvironment environment = preproductionRuntime("sanitized")
                .withProperty("app.staging.e2e-mutation-enabled", "false")
                .withProperty("app.staging.account-provisioning-enabled", "true")
                .withProperty(
                        "app.preproduction-database.e2e-before-evidence-seal-sha256", "");
        Flyway flyway = flywayWithPending(new MigrationInfo[0]);

        configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway);

        verify(runtimeSafetyState).recordCompletedClone(
                "sanitized", "release-sentinel", SOURCE_SNAPSHOT, "release-guard-test",
                "viralground_release_staging", EVIDENCE_SEAL, "");
        verify(flyway).validate();
        verify(flyway, never()).migrate();
    }

    @Test
    void guardedRunnerRequiresExplicitPublicSchema() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("app.migration-runner.enabled", "true")
                .withProperty("app.migration-runner.clone-kind", "exact")
                .withProperty("app.migration-runner.sentinel-id", "schema-guard-sentinel")
                .withProperty("app.migration-runner.source-snapshot-id", SOURCE_SNAPSHOT)
                .withProperty("app.migration-runner.allowed-hosts", "clone.example.test")
                .withProperty("app.migration-runner.allowed-databases", "viralground_schema_exact")
                .withProperty("app.migration-runner.production-host", "production.example.test")
                .withProperty("app.migration-runner.production-database", "viralground_prod")
                .withProperty("app.migration-runner.database-confirmation",
                        "I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE")
                .withProperty("app.migration-runner.migration-confirmation",
                        "BASELINE_V1_ON_DISPOSABLE_EXACT_CLONE_ONCE")
                .withProperty("app.release-id", "vg-schema-guard-test")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://clone.example.test:5432/viralground_schema_exact"
                                + "?sslmode=verify-full");
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> configuration.guardedFlywayMigrationStrategy(
                environment, dataSource, safetyValidator, runtimeSafetyState).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentSchema=public");
        verify(flyway, never()).migrate();
    }

    private Flyway flywayWithPending(MigrationInfo[] pending) {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(pending);
        return flyway;
    }

    private MockEnvironment preproductionRuntime(String cloneKind) {
        String database = cloneKind.equals("exact")
                ? "viralground_release_exact" : "viralground_release_staging";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("app.migration-runner.enabled", "false")
                .withProperty("app.preproduction-database.clone-kind", cloneKind)
                .withProperty("app.preproduction-database.sentinel-id", "release-sentinel")
                .withProperty("app.preproduction-database.source-snapshot-id", SOURCE_SNAPSHOT)
                .withProperty("app.preproduction-database.allowed-hosts", "clone.example.test")
                .withProperty("app.preproduction-database.allowed-databases", database)
                .withProperty("app.preproduction-database.production-host", "db.example.test")
                .withProperty("app.preproduction-database.production-database", "viralground_live")
                .withProperty("app.preproduction-database.database-confirmation",
                        "I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE")
                .withProperty("app.preproduction-database.evidence-seal-sha256", EVIDENCE_SEAL)
                .withProperty("app.release-id", "release-guard-test")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://clone.example.test:5432/" + database
                                + "?sslmode=verify-full&currentSchema=public");
        if ("sanitized".equals(cloneKind)) {
            environment.withProperty("app.staging.e2e-mutation-enabled", "true")
                    .withProperty(
                            "app.preproduction-database.e2e-before-evidence-seal-sha256",
                            E2E_BEFORE_SEAL);
        }
        return environment;
    }

    private Connection mockCompletedRuntimeConnection(boolean completed) throws Exception {
        return mockCompletedRuntimeConnection(completed, EVIDENCE_SEAL, E2E_BEFORE_SEAL);
    }

    private Connection mockCompletedRuntimeConnection(
            boolean completed, String liveEvidenceSeal) throws Exception {
        return mockCompletedRuntimeConnection(completed, liveEvidenceSeal, E2E_BEFORE_SEAL);
    }

    private Connection mockCompletedRuntimeConnection(
            boolean completed,
            String liveEvidenceSeal,
            String liveE2eBeforeEvidenceSeal) throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet result = mock(ResultSet.class);
            when(statement.executeQuery()).thenReturn(result);
            if (sql.contains("current_database")) {
                when(result.next()).thenReturn(true);
                when(result.getString(1)).thenReturn("viralground_release_staging");
            } else if (sql.contains("preprod_guard.clone_sentinel")) {
                when(result.next()).thenReturn(true, false);
                when(result.getBoolean(1)).thenReturn(true);
                when(result.getBoolean(2)).thenReturn(completed);
                when(result.getString(3)).thenReturn(liveEvidenceSeal);
                when(result.getBoolean(4)).thenReturn(true);
                when(result.getString(5)).thenReturn(liveE2eBeforeEvidenceSeal);
            } else if (sql.contains("to_regclass")) {
                when(result.next()).thenReturn(true);
                when(result.getBoolean(1)).thenReturn(true);
            } else if (sql.contains("flyway_schema_history")) {
                when(result.next()).thenReturn(true);
                when(result.getLong(1)).thenReturn(1L);
            } else {
                throw new AssertionError("unexpected SQL: " + sql);
            }
            return statement;
        });
        return connection;
    }
}

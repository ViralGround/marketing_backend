package com.viralground.backend.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Makes Flyway fail closed in every protected environment.
 *
 * <p>A normal staging/production boot validates the already-migrated schema and
 * refuses pending migrations. Only the one-shot clone runner may call migrate(),
 * and it must independently repeat the target, sentinel, legacy-data and baseline
 * guards inside the JVM before Flyway can create its history table. Shell wrappers
 * remain operator UX/evidence tooling, not the security boundary.</p>
 */
@Configuration(proxyBeanMethods = false)
public class GuardedFlywayMigrationConfiguration {
    private static final Set<String> SUPPORTED_APP_ENVIRONMENTS = Set.of(
            "development", "test", "preproduction", "production");
    private static final Set<String> PROTECTED_APP_ENVIRONMENTS = Set.of(
            "preproduction", "production");
    private static final Set<String> REJECTED_PROTECTED_ALIASES = Set.of(
            "prod", "staging", "pre-production");
    private static final String DATABASE_CONFIRMATION =
            "I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE";
    private static final String EXACT_MIGRATION_CONFIRMATION =
            "BASELINE_V1_ON_DISPOSABLE_EXACT_CLONE_ONCE";
    private static final String SANITIZED_MIGRATION_CONFIRMATION =
            "BASELINE_V1_ON_DISPOSABLE_SANITIZED_CLONE_ONCE";
    private static final Pattern PRODUCTION_HOST_MARKER =
            Pattern.compile("(^|[.-])(prod|production)([.-]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCTION_DATABASE_MARKER =
            Pattern.compile("(^|[_-])(prod|production)([_-]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_SNAPSHOT_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,255}$");

    @Bean
    FlywayMigrationStrategy guardedFlywayMigrationStrategy(
            Environment environment,
            DataSource dataSource,
            ProductionSafetyValidator productionSafetyValidator,
            RuntimeSafetyState runtimeSafetyState) {
        // Requiring this bean makes its InitializingBean validation a hard dependency
        // of FlywayMigrationInitializer rather than relying on incidental bean order.
        Objects.requireNonNull(productionSafetyValidator, "productionSafetyValidator");

        return flyway -> {
            if (!isProtectedEnvironment(environment)) {
                if (isMigrationRunner(environment)) {
                    throw new IllegalStateException(
                            "guarded migration runner requires a protected preproduction environment");
                }
                assertAutomaticMigrationIsLocal(environment);
                flyway.migrate();
                return;
            }

            if (!isMigrationRunner(environment)) {
                if (isPreproduction(environment)) {
                    RuntimeTargetGuard target = RuntimeTargetGuard.from(environment);
                    boolean exactCompatibility = environment.getProperty(
                            "app.exact-compatibility.enabled", Boolean.class, false);
                    String requiredKind = exactCompatibility ? "exact" : "sanitized";
                    if (!requiredKind.equals(target.cloneKind())) {
                        throw new IllegalStateException(exactCompatibility
                                ? "exact compatibility mode requires a completed exact clone"
                                : "normal preproduction runtime requires a completed sanitized clone");
                    }
                    guardCompletedCloneRuntime(dataSource, target);
                    runtimeSafetyState.recordCompletedClone(
                            target.cloneKind(),
                            target.sentinelId(),
                            target.sourceSnapshotId(),
                            target.releaseId(),
                            target.database(),
                            target.evidenceSealSha256(),
                            target.e2eBeforeEvidenceSealSha256());
                } else if (environment.getProperty(
                        "app.exact-compatibility.enabled", Boolean.class, false)) {
                    throw new IllegalStateException(
                            "exact compatibility mode is permitted only in preproduction");
                }
                validateOnlyWithNoPending(flyway);
                return;
            }

            if (!isPreproduction(environment)) {
                throw new IllegalStateException(
                        "guarded migration runner is forbidden outside preproduction/staging");
            }

            TargetGuard target = TargetGuard.from(environment);
            boolean baselineEnabled = flyway.getConfiguration().isBaselineOnMigrate();
            if (!"1".equals(flyway.getConfiguration().getBaselineVersion().getVersion())) {
                throw new IllegalStateException("guarded migration runner requires Flyway baseline version 1");
            }
            guardCloneBeforeFlyway(dataSource, target, baselineEnabled);

            if (baselineEnabled) {
                flyway.migrate();
            } else {
                validateOnlyWithNoPending(flyway);
            }
        };
    }

    private static void validateOnlyWithNoPending(Flyway flyway) {
        flyway.validate();
        if (flyway.info().pending().length != 0) {
            throw new IllegalStateException(
                    "protected runtime has pending Flyway migrations; use the guarded clone migration workflow");
        }
    }

    /**
     * APP_ENV is operator input, so it cannot by itself authorize automatic
     * migration. Development/test auto-migrate is limited to H2 or a loopback
     * PostgreSQL database. Clone suffixes still require the guarded runner, except
     * for the repository's explicitly synthetic local Compose database.
     */
    private static void assertAutomaticMigrationIsLocal(Environment environment) {
        String jdbcUrl = environment.getProperty("spring.datasource.url", "").trim();
        if (jdbcUrl.startsWith("jdbc:h2:")) {
            return;
        }
        JdbcTarget target = TargetGuard.parseJdbcTarget(jdbcUrl);
        String host = TargetGuard.normalizeHost(target.host());
        String database = target.database().toLowerCase(Locale.ROOT);
        boolean loopback = Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
                .contains(host);
        if (!loopback) {
            throw new IllegalStateException(
                    "automatic Flyway migration is restricted to a loopback development database");
        }
        if (PRODUCTION_HOST_MARKER.matcher(host).find()
                || PRODUCTION_DATABASE_MARKER.matcher(database).find()) {
            throw new IllegalStateException(
                    "automatic Flyway migration refuses a production-marked target");
        }
        if (database.endsWith("_exact")) {
            throw new IllegalStateException(
                    "exact clones require the protected guarded migration runner");
        }
        if (database.endsWith("_staging") && !database.equals("viralground_local_staging")) {
            throw new IllegalStateException(
                    "staging clones require the protected guarded migration runner");
        }
    }

    private static void guardCloneBeforeFlyway(
            DataSource dataSource, TargetGuard target, boolean baselineEnabled) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(false);
            try {
                String currentDatabase = scalarText(connection, "SELECT current_database()");
                if (!target.database().equals(currentDatabase)) {
                    throw new IllegalStateException("connected database does not match guarded JDBC target");
                }

                SentinelState sentinel = readSentinel(connection, target);
                boolean historyExists = relationExists(connection, "public.flyway_schema_history");
                if (baselineEnabled) {
                    if (historyExists) {
                        throw new IllegalStateException(
                                "baseline-enabled runner refuses an existing Flyway history");
                    }
                    if (sentinel.baselineStarted() || sentinel.baselineCompleted()) {
                        throw new IllegalStateException(
                                "baseline was already attempted for this clone sentinel");
                    }
                    long blockers = scalarLong(connection, LEGACY_PREFLIGHT_BLOCKERS_SQL);
                    if (blockers != 0) {
                        throw new IllegalStateException(
                                "clone legacy preflight has " + blockers + " blocking rows");
                    }
                    if (target.cloneKind().equals("sanitized")) {
                        long sensitiveRows = scalarLong(
                                connection, LEGACY_SANITIZATION_BLOCKERS_SQL);
                        if (sensitiveRows != 0) {
                            throw new IllegalStateException(
                                    "sanitized clone still has " + sensitiveRows
                                            + " sensitive legacy rows");
                        }
                    }
                    int transitioned = markBaselineStarted(connection, target);
                    if (transitioned != 1) {
                        throw new IllegalStateException(
                                "clone sentinel baseline transition lost its one-shot compare-and-set");
                    }
                } else {
                    if (!historyExists || !sentinel.baselineStarted() || sentinel.baselineCompleted()) {
                        throw new IllegalStateException(
                                "baseline-disabled runner requires an active, started one-shot sentinel");
                    }
                    if (successfulV1BaselineCount(connection) != 1) {
                        throw new IllegalStateException(
                                "baseline-disabled runner requires exactly one successful V1 baseline");
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "guarded clone validation failed before Flyway was allowed to run", failure);
        }
    }

    /**
     * A normal staging process may only read a release-bound sanitized clone
     * after the guarded migration/evidence workflow sealed its sentinel. The
     * separate exact-compatibility mode uses the same completed-state check but
     * remains validate-only. This prevents a direct application boot between the
     * runner's started CAS and its final evidence comparison/seal.
     */
    private static void guardCompletedCloneRuntime(
            DataSource dataSource, RuntimeTargetGuard target) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            String currentDatabase = scalarText(connection, "SELECT current_database()");
            if (!target.database().equals(currentDatabase)) {
                throw new IllegalStateException(
                        "connected database does not match guarded preproduction target");
            }

            SentinelState sentinel = readSentinel(connection, target);
            if (!sentinel.baselineStarted() || !sentinel.baselineCompleted()) {
                throw new IllegalStateException(
                        "preproduction clone sentinel is not migration/evidence complete");
            }
            if (!target.evidenceSealSha256().equalsIgnoreCase(
                    sentinel.evidenceSealSha256())) {
                throw new IllegalStateException(
                        "preproduction clone evidence seal does not match the expected release evidence");
            }
            if ("sanitized".equals(target.cloneKind())
                    && target.stagingE2eMutationEnabled()
                    && (!sentinel.e2eBeforeRecorded()
                    || !target.e2eBeforeEvidenceSealSha256().equalsIgnoreCase(
                    sentinel.e2eBeforeEvidenceSealSha256()))) {
                throw new IllegalStateException(
                        "sanitized clone E2E-before evidence seal is missing or mismatched");
            }
            if (!relationExists(connection, "public.flyway_schema_history")
                    || successfulV1BaselineCount(connection) != 1) {
                throw new IllegalStateException(
                        "preproduction clone does not contain exactly one successful V1 baseline");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "completed preproduction clone validation failed", failure);
        }
    }

    private static SentinelState readSentinel(Connection connection, CloneTarget target)
            throws SQLException {
        String sql = """
                SELECT baseline_started_at IS NOT NULL,
                       baseline_completed_at IS NOT NULL,
                       evidence_seal_sha256,
                       e2e_before_recorded_at IS NOT NULL,
                       e2e_before_evidence_seal_sha256
                FROM preprod_guard.clone_sentinel
                WHERE sentinel_id = ?
                  AND clone_kind = ?
                  AND source_snapshot_id = ?
                  AND release_id = ?
                  AND destroyed_at IS NULL
                  AND created_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
                  AND expires_at > CURRENT_TIMESTAMP
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.sentinelId());
            statement.setString(2, target.cloneKind());
            statement.setString(3, target.sourceSnapshotId());
            statement.setString(4, target.releaseId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("missing, expired, destroyed, or mismatched clone sentinel");
                }
                SentinelState state = new SentinelState(
                        result.getBoolean(1),
                        result.getBoolean(2),
                        result.getString(3),
                        result.getBoolean(4),
                        result.getString(5));
                if (result.next()) {
                    throw new IllegalStateException("clone sentinel lookup was not unique");
                }
                return state;
            }
        }
    }

    private static int markBaselineStarted(Connection connection, TargetGuard target)
            throws SQLException {
        String sql = """
                UPDATE preprod_guard.clone_sentinel
                SET baseline_started_at = CURRENT_TIMESTAMP
                WHERE sentinel_id = ?
                  AND clone_kind = ?
                  AND source_snapshot_id = ?
                  AND release_id = ?
                  AND destroyed_at IS NULL
                  AND expires_at > CURRENT_TIMESTAMP
                  AND baseline_started_at IS NULL
                  AND baseline_completed_at IS NULL
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.sentinelId());
            statement.setString(2, target.cloneKind());
            statement.setString(3, target.sourceSnapshotId());
            statement.setString(4, target.releaseId());
            return statement.executeUpdate();
        }
    }

    private static int successfulV1BaselineCount(Connection connection) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '1' AND type = 'BASELINE' AND success = TRUE
                """;
        return Math.toIntExact(scalarLong(connection, sql));
    }

    private static boolean relationExists(Connection connection, String qualifiedName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, qualifiedName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static String scalarText(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("guard query returned no row");
            }
            return result.getString(1);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("guard query returned no row");
            }
            return result.getLong(1);
        }
    }

    private static boolean isMigrationRunner(Environment environment) {
        return environment.getProperty("app.migration-runner.enabled", Boolean.class, false);
    }

    private static boolean isProtectedEnvironment(Environment environment) {
        String appEnvironment = appEnvironment(environment);
        return PROTECTED_APP_ENVIRONMENTS.contains(appEnvironment)
                || Arrays.stream(environment.getActiveProfiles())
                .anyMatch(PROTECTED_APP_ENVIRONMENTS::contains);
    }

    private static boolean isPreproduction(Environment environment) {
        String appEnvironment = appEnvironment(environment);
        return "preproduction".equals(appEnvironment)
                || Arrays.stream(environment.getActiveProfiles())
                .anyMatch("preproduction"::equals);
    }

    private static String appEnvironment(Environment environment) {
        String appEnvironment = environment.getProperty("app.environment", "development");
        if (!SUPPORTED_APP_ENVIRONMENTS.contains(appEnvironment)) {
            throw new IllegalStateException(
                    "APP_ENV must exactly equal development, test, preproduction, or production");
        }
        for (String profile : environment.getActiveProfiles()) {
            String normalized = profile.strip().toLowerCase(Locale.ROOT);
            boolean protectedName = PROTECTED_APP_ENVIRONMENTS.contains(normalized)
                    || REJECTED_PROTECTED_ALIASES.contains(normalized);
            if (protectedName && !PROTECTED_APP_ENVIRONMENTS.contains(profile)) {
                throw new IllegalStateException(
                        "protected Spring profile must exactly equal preproduction or production");
            }
        }
        Set<String> protectedProfiles = Arrays.stream(environment.getActiveProfiles())
                .filter(PROTECTED_APP_ENVIRONMENTS::contains)
                .collect(Collectors.toUnmodifiableSet());
        if (!protectedProfiles.isEmpty()
                && !protectedProfiles.equals(Set.of(appEnvironment))) {
            throw new IllegalStateException(
                    "APP_ENV and active protected Spring profile must exactly match");
        }
        return appEnvironment;
    }

    private record SentinelState(
            boolean baselineStarted,
            boolean baselineCompleted,
            String evidenceSealSha256,
            boolean e2eBeforeRecorded,
            String e2eBeforeEvidenceSealSha256) {
    }

    private interface CloneTarget {
        String cloneKind();
        String sentinelId();
        String sourceSnapshotId();
        String releaseId();
        String host();
        String database();
    }

    private record TargetGuard(
            String cloneKind,
            String sentinelId,
            String sourceSnapshotId,
            String releaseId,
            String host,
            String database) implements CloneTarget {

        private static TargetGuard from(Environment environment) {
            String cloneKind = required(environment, "app.migration-runner.clone-kind")
                    .toLowerCase(Locale.ROOT);
            if (!Set.of("exact", "sanitized").contains(cloneKind)) {
                throw new IllegalStateException("migration clone kind must be exact or sanitized");
            }

            String expectedMigrationConfirmation = cloneKind.equals("exact")
                    ? EXACT_MIGRATION_CONFIRMATION : SANITIZED_MIGRATION_CONFIRMATION;
            if (!DATABASE_CONFIRMATION.equals(
                    required(environment, "app.migration-runner.database-confirmation"))) {
                throw new IllegalStateException("guarded database confirmation does not match");
            }
            if (!expectedMigrationConfirmation.equals(
                    required(environment, "app.migration-runner.migration-confirmation"))) {
                throw new IllegalStateException("clone-specific migration confirmation does not match");
            }

            JdbcTarget jdbcTarget = parseJdbcTarget(required(environment, "spring.datasource.url"));
            String host = normalizeHost(jdbcTarget.host());
            String database = jdbcTarget.database();
            String productionHost = normalizeHost(
                    required(environment, "app.migration-runner.production-host"));
            String productionDatabase =
                    required(environment, "app.migration-runner.production-database");

            if (host.equals(productionHost) || database.equals(productionDatabase)) {
                throw new IllegalStateException("guarded runner target matches declared production");
            }
            if (PRODUCTION_HOST_MARKER.matcher(host).find()
                    || PRODUCTION_DATABASE_MARKER.matcher(database).find()) {
                throw new IllegalStateException("guarded runner target contains a production marker");
            }

            Set<String> allowedHosts = csv(environment,
                    "app.migration-runner.allowed-hosts", true);
            Set<String> allowedDatabases = csv(environment,
                    "app.migration-runner.allowed-databases", false);
            if (!allowedHosts.contains(host) || !allowedDatabases.contains(database)) {
                throw new IllegalStateException("guarded runner target is outside its exact allowlist");
            }

            String requiredSuffix = cloneKind.equals("exact") ? "_exact" : "_staging";
            if (!database.endsWith(requiredSuffix)) {
                throw new IllegalStateException(
                        "guarded runner database has the wrong clone-kind suffix");
            }
            if (!isLoopbackHost(host) && !"verify-full".equals(jdbcTarget.sslMode())) {
                throw new IllegalStateException(
                        "remote guarded runner requires PostgreSQL sslmode=verify-full");
            }
            if (isLoopbackHost(host)
                    && !Set.of("require", "verify-ca", "verify-full").contains(jdbcTarget.sslMode())) {
                throw new IllegalStateException("local guarded runner requires PostgreSQL TLS sslmode");
            }
            if (!"public".equalsIgnoreCase(jdbcTarget.currentSchema())) {
                throw new IllegalStateException(
                        "guarded runner requires an explicit PostgreSQL currentSchema=public");
            }

            return new TargetGuard(
                    cloneKind,
                    required(environment, "app.migration-runner.sentinel-id"),
                    requiredSourceSnapshotId(
                            environment, "app.migration-runner.source-snapshot-id"),
                    required(environment, "app.release-id"),
                    host,
                    database);
        }

        private static Set<String> csv(
                Environment environment, String property, boolean normalizeHosts) {
            Set<String> values = Arrays.stream(required(environment, property).split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> normalizeHosts ? normalizeHost(value) : value)
                    .collect(Collectors.toUnmodifiableSet());
            if (values.isEmpty()) {
                throw new IllegalStateException("guarded runner allowlist is empty: " + property);
            }
            return values;
        }

        private static String required(Environment environment, String property) {
            String value = environment.getProperty(property, "").trim();
            if (value.isBlank()) {
                throw new IllegalStateException("guarded runner property is empty: " + property);
            }
            return value;
        }

        private static String requiredSourceSnapshotId(
                Environment environment, String property) {
            String value = required(environment, property);
            if (!SOURCE_SNAPSHOT_ID.matcher(value).matches()) {
                throw new IllegalStateException(
                        "guarded runner source snapshot identifier is invalid");
            }
            return value;
        }

        private static JdbcTarget parseJdbcTarget(String jdbcUrl) {
            String prefix = "jdbc:postgresql://";
            if (!jdbcUrl.startsWith(prefix)) {
                throw new IllegalStateException("guarded runner requires a PostgreSQL JDBC URL");
            }
            URI uri;
            try {
                uri = new URI("postgresql://" + jdbcUrl.substring(prefix.length()));
            } catch (URISyntaxException invalid) {
                throw new IllegalStateException("guarded runner JDBC URL is invalid", invalid);
            }
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPath() == null || uri.getPath().length() <= 1
                    || uri.getAuthority().contains(",")) {
                throw new IllegalStateException("guarded runner JDBC target must contain one host and database");
            }
            String database = uri.getPath().substring(1);
            if (database.contains("/")) {
                throw new IllegalStateException("guarded runner JDBC database path is invalid");
            }
            String sslMode = uniqueSecurityQueryParameter(uri, "sslmode")
                    .toLowerCase(Locale.ROOT);
            String currentSchema = uniqueSecurityQueryParameter(uri, "currentSchema");
            return new JdbcTarget(uri.getHost(), database, sslMode, currentSchema);
        }

        private static String uniqueSecurityQueryParameter(URI uri, String expectedName) {
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.isEmpty()) return "";
            String found = null;
            for (String segment : rawQuery.split("&", -1)) {
                String[] parts = segment.split("=", 2);
                if (parts.length != 2) continue;
                String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                if (!name.equalsIgnoreCase(expectedName)) continue;
                if (found != null) {
                    throw new IllegalStateException(
                            "guarded runner JDBC URL contains duplicate " + expectedName);
                }
                found = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
            return found == null ? "" : found;
        }

        private static String normalizeHost(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            return normalized.endsWith(".")
                    ? normalized.substring(0, normalized.length() - 1) : normalized;
        }

        private static boolean isLoopbackHost(String host) {
            return Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
                    .contains(normalizeHost(host));
        }
    }

    private record RuntimeTargetGuard(
            String cloneKind,
            String sentinelId,
            String sourceSnapshotId,
            String releaseId,
            String host,
            String database,
            String evidenceSealSha256,
            String e2eBeforeEvidenceSealSha256,
            boolean stagingE2eMutationEnabled) implements CloneTarget {

        private static RuntimeTargetGuard from(Environment environment) {
            String prefix = "app.preproduction-database.";
            String cloneKind = required(environment, prefix + "clone-kind")
                    .toLowerCase(Locale.ROOT);
            if (!Set.of("exact", "sanitized").contains(cloneKind)) {
                throw new IllegalStateException(
                        "preproduction database clone kind must be exact or sanitized");
            }
            if (!DATABASE_CONFIRMATION.equals(
                    required(environment, prefix + "database-confirmation"))) {
                throw new IllegalStateException(
                        "preproduction database confirmation does not match");
            }

            JdbcTarget jdbcTarget = TargetGuard.parseJdbcTarget(
                    required(environment, "spring.datasource.url"));
            String host = TargetGuard.normalizeHost(jdbcTarget.host());
            String database = jdbcTarget.database();
            String productionHost = TargetGuard.normalizeHost(
                    required(environment, prefix + "production-host"));
            String productionDatabase = required(
                    environment, prefix + "production-database");
            if (host.equals(productionHost) || database.equals(productionDatabase)) {
                throw new IllegalStateException(
                        "preproduction runtime target matches declared production");
            }
            if (PRODUCTION_HOST_MARKER.matcher(host).find()
                    || PRODUCTION_DATABASE_MARKER.matcher(database).find()) {
                throw new IllegalStateException(
                        "preproduction runtime target contains a production marker");
            }

            Set<String> allowedHosts = TargetGuard.csv(
                    environment, prefix + "allowed-hosts", true);
            Set<String> allowedDatabases = TargetGuard.csv(
                    environment, prefix + "allowed-databases", false);
            if (!allowedHosts.contains(host) || !allowedDatabases.contains(database)) {
                throw new IllegalStateException(
                        "preproduction runtime target is outside its exact allowlist");
            }
            String requiredSuffix = cloneKind.equals("exact") ? "_exact" : "_staging";
            if (!database.endsWith(requiredSuffix)) {
                throw new IllegalStateException(
                        "preproduction runtime database has the wrong clone-kind suffix");
            }
            if (!TargetGuard.isLoopbackHost(host)
                    && !"verify-full".equals(jdbcTarget.sslMode())) {
                throw new IllegalStateException(
                        "remote preproduction runtime requires PostgreSQL sslmode=verify-full");
            }
            if (TargetGuard.isLoopbackHost(host)
                    && !Set.of("require", "verify-ca", "verify-full")
                    .contains(jdbcTarget.sslMode())) {
                throw new IllegalStateException(
                        "local preproduction runtime requires PostgreSQL TLS sslmode");
            }
            if (!"public".equalsIgnoreCase(jdbcTarget.currentSchema())) {
                throw new IllegalStateException(
                        "preproduction runtime requires an explicit PostgreSQL currentSchema=public");
            }

            String evidenceSealSha256 = required(
                    environment, prefix + "evidence-seal-sha256");
            if (!evidenceSealSha256.matches("(?i)^[0-9a-f]{64}$")) {
                throw new IllegalStateException(
                        "preproduction evidence seal must be exactly 64 hexadecimal characters");
            }
            String e2eBeforeEvidenceSealSha256 = environment.getProperty(
                    prefix + "e2e-before-evidence-seal-sha256", "").trim();
            boolean accountProvisioningEnabled = environment.getProperty(
                    "app.staging.account-provisioning-enabled", Boolean.class, false);
            boolean stagingE2eMutationEnabled = environment.getProperty(
                    "app.staging.e2e-mutation-enabled", Boolean.class, false);
            boolean stagingEmailValidationEnabled = environment.getProperty(
                    "app.staging.email-validation-enabled", Boolean.class, false);
            int enabledMutationModes = (accountProvisioningEnabled ? 1 : 0)
                    + (stagingE2eMutationEnabled ? 1 : 0)
                    + (stagingEmailValidationEnabled ? 1 : 0);
            if (enabledMutationModes > 1) {
                throw new IllegalStateException(
                        "staging provisioning, E2E, and email validation modes are mutually exclusive");
            }
            if ("exact".equals(cloneKind)
                    && enabledMutationModes > 0) {
                throw new IllegalStateException(
                        "exact compatibility runtime cannot enable staging mutation modes");
            }
            if ("sanitized".equals(cloneKind)
                    && (stagingE2eMutationEnabled || stagingEmailValidationEnabled)
                    && !e2eBeforeEvidenceSealSha256.matches("(?i)^[0-9a-f]{64}$")) {
                throw new IllegalStateException(
                        "sanitized runtime E2E-before evidence seal must be exactly 64 hexadecimal characters");
            }
            if (!e2eBeforeEvidenceSealSha256.isEmpty()
                    && !e2eBeforeEvidenceSealSha256.matches("(?i)^[0-9a-f]{64}$")) {
                throw new IllegalStateException(
                        "preproduction E2E-before evidence seal is invalid");
            }
            if (!stagingE2eMutationEnabled && !stagingEmailValidationEnabled
                    && !e2eBeforeEvidenceSealSha256.isEmpty()) {
                throw new IllegalStateException(
                        "E2E-before evidence seal must be empty outside E2E/email validation mode");
            }

            return new RuntimeTargetGuard(
                    cloneKind,
                    required(environment, prefix + "sentinel-id"),
                    TargetGuard.requiredSourceSnapshotId(
                            environment, prefix + "source-snapshot-id"),
                    required(environment, "app.release-id"),
                    host,
                    database,
                    evidenceSealSha256.toLowerCase(Locale.ROOT),
                    e2eBeforeEvidenceSealSha256.toLowerCase(Locale.ROOT),
                    stagingE2eMutationEnabled);
        }

        private static String required(Environment environment, String property) {
            String value = environment.getProperty(property, "").trim();
            if (value.isBlank()) {
                throw new IllegalStateException(
                        "preproduction database property is empty: " + property);
            }
            return value;
        }
    }

    private record JdbcTarget(String host, String database, String sslMode, String currentSchema) {
    }

    private static final String LEGACY_PREFLIGHT_BLOCKERS_SQL = """
            SELECT
              (SELECT COUNT(*) FROM escrow_transactions WHERE amount <= 0)
            + (SELECT COUNT(*) FROM escrow_transactions
               WHERE type NOT IN ('DEPOSIT', 'RELEASE', 'REFUND'))
            + (SELECT COUNT(*) FROM (
                 SELECT campaign_id FROM escrow_transactions WHERE type = 'DEPOSIT'
                 GROUP BY campaign_id HAVING COUNT(*) > 1
              ) value)
            + (SELECT COUNT(*) FROM (
                 SELECT campaign_id FROM escrow_transactions WHERE type = 'REFUND'
                 GROUP BY campaign_id HAVING COUNT(*) > 1
              ) value)
            + (SELECT COUNT(*) FROM (
                 SELECT campaign_id, application_id FROM escrow_transactions
                 WHERE type = 'RELEASE'
                 GROUP BY campaign_id, application_id HAVING COUNT(*) > 1
              ) value)
            + (SELECT COUNT(*) FROM (
                 SELECT SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE -amount END)
                          OVER (PARTITION BY campaign_id ORDER BY created_at, id) AS balance
                 FROM escrow_transactions
              ) value WHERE balance < 0)
            + (SELECT COUNT(*) FROM (
                 SELECT provider_account_id FROM creator_instagram_connections
                 WHERE provider_account_id IS NOT NULL
                 GROUP BY provider_account_id HAVING COUNT(*) > 1
              ) value)
            + (SELECT COUNT(*) FROM members
               WHERE status NOT IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
            + (SELECT COUNT(*) FROM members WHERE status = 'WITHDRAWN')
            + (SELECT COUNT(*) FROM campaign_applications
               WHERE status NOT IN ('PENDING', 'WITHDRAWN', 'APPROVED', 'REJECTED',
                                    'SUBMITTED', 'CHANGES_REQUESTED', 'SETTLED'))
            + (SELECT COUNT(*) FROM campaigns
               WHERE reward_amount NOT BETWEEN 1 AND 100000000
                  OR max_participants NOT BETWEEN 1 AND 10000
                  OR total_budget <= 0
                  OR total_budget::bigint <> reward_amount::bigint * max_participants::bigint)
            """;

    private static final String LEGACY_SANITIZATION_BLOCKERS_SQL = """
            SELECT
              (SELECT COUNT(*) FROM members
               WHERE email !~ '^member\\+[0-9]+@example[.]invalid$'
                  OR password IS DISTINCT FROM '!SANITIZED-DISABLED!'
                  OR name !~ '^Sanitized Member [0-9]+$')
            + (SELECT COUNT(*) FROM creator_profiles
               WHERE profile_image IS NOT NULL OR instagram_id IS NOT NULL
                  OR tiktok_id IS NOT NULL OR youtube_id IS NOT NULL)
            + (SELECT COUNT(*) FROM company_profiles
               WHERE address IS NOT NULL OR homepage IS NOT NULL OR introduction IS NOT NULL
                  OR logo_file_key IS NOT NULL
                  OR company_name IS DISTINCT FROM 'Sanitized Company ' || id
                  OR business_number IS DISTINCT FROM lpad((id::bigint % 10000000000)::text, 10, '0')
                  OR representative_name IS DISTINCT FROM 'Representative ' || id
                  OR contact_name IS DISTINCT FROM 'Contact ' || id
                  OR contact_phone IS DISTINCT FROM
                     '000-0000-' || lpad((id::bigint % 10000)::text, 4, '0'))
            + (SELECT COUNT(*) FROM campaigns
               WHERE brand_introduction IS NOT NULL OR brand_logo_file_key IS NOT NULL
                  OR thumbnail_url IS NOT NULL OR thumbnail_file_key IS NOT NULL
                  OR requirements IS NOT NULL
                  OR title IS DISTINCT FROM 'Sanitized Campaign ' || id
                  OR description IS DISTINCT FROM 'Sanitized campaign fixture'
                  OR brand_name IS DISTINCT FROM 'Sanitized Brand ' || id)
            + (SELECT COUNT(*) FROM campaign_applications
               WHERE message IS NOT NULL OR submission_url IS NOT NULL
                  OR video_file_key IS NOT NULL OR video_content_type IS NOT NULL
                  OR video_size_bytes IS NOT NULL OR review_comment IS NOT NULL)
            + (SELECT COUNT(*) FROM application_submissions
               WHERE video_file_key IS NOT NULL OR video_content_type IS NOT NULL
                  OR video_size_bytes IS NOT NULL OR submission_url IS NOT NULL
                  OR review_comment IS NOT NULL)
            + (SELECT COUNT(*) FROM reviews WHERE comment IS NOT NULL)
            + (SELECT COUNT(*) FROM submission_metrics WHERE external_url IS NOT NULL)
            + (SELECT COUNT(*) FROM creator_instagram_connections
               WHERE provider_user_id IS NOT NULL OR provider_account_id IS NOT NULL
                  OR ig_username IS NOT NULL OR last_error IS NOT NULL
                  OR connected_at IS NOT NULL OR last_synced_at IS NOT NULL
                  OR status <> 'DISCONNECTED')
            + (SELECT COUNT(*) FROM contact_requests
               WHERE email !~ '^contact\\+[0-9]+@example[.]invalid$'
                  OR brand_name IS DISTINCT FROM 'Sanitized Contact Brand ' || id
                  OR contact_name IS NOT NULL)
            + (SELECT COUNT(*) FROM email_verification_codes)
            + (SELECT COUNT(*) FROM escrow_transactions WHERE memo IS NOT NULL)
            """;
}

package com.viralground.backend.config;

import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure, bean-free database target boundary shared by early boot and runtime validation. */
final class DatabaseEnvironmentBoundary {
    private static final String CLONE_CONFIRMATION =
            "I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE";
    private static final String VERIFIED_TEST_RUNTIME_PROPERTY =
            "viralground.verified-test-runtime";
    private static final Set<String> SUPPORTED_APP_ENVIRONMENTS = Set.of(
            "development", "test", "preproduction", "production");
    private static final Set<String> PROTECTED_APP_ENVIRONMENTS = Set.of(
            "preproduction", "production");
    private static final Set<String> REJECTED_PROTECTED_ALIASES = Set.of(
            "prod", "staging", "pre-production");
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private DatabaseEnvironmentBoundary() {
    }

    static void validate(Environment environment) {
        String appEnvironment = environment.getProperty(
                "app.environment", "development");
        validateEnvironmentIdentity(environment, appEnvironment);
        boolean protectedEnvironment =
                PROTECTED_APP_ENVIRONMENTS.contains(appEnvironment);

        String jdbcUrl;
        try {
            jdbcUrl = environment.getProperty("spring.datasource.url", "").trim();
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            throw new IllegalStateException(
                    "spring.datasource.url could not be resolved before datasource creation",
                    unresolvedPlaceholder);
        }
        if (jdbcUrl.isBlank()) {
            if (protectedEnvironment) {
                throw new IllegalStateException(
                        "protected runtime requires an explicit remote PostgreSQL datasource");
            }
            return;
        }
        if (jdbcUrl.startsWith("jdbc:h2:")) {
            if (protectedEnvironment) {
                throw new IllegalStateException(
                        "protected runtime refuses an embedded H2 datasource");
            }
            return;
        }

        String prefix = "jdbc:postgresql://";
        if (!jdbcUrl.startsWith(prefix)) {
            throw new IllegalStateException(
                    "unsupported JDBC target outside the local H2/PostgreSQL boundary");
        }

        URI uri;
        try {
            uri = URI.create("postgresql://" + jdbcUrl.substring(prefix.length()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("PostgreSQL JDBC target is invalid", invalid);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getAuthority().contains(",")) {
            throw new IllegalStateException(
                    "PostgreSQL JDBC target must contain one host");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        boolean loopback = LOOPBACK_HOSTS.contains(host);
        if (loopback && protectedEnvironment) {
            throw new IllegalStateException(
                    "protected runtime refuses a loopback PostgreSQL target");
        }
        if (!loopback && !protectedEnvironment) {
            throw new IllegalStateException(
                    "remote PostgreSQL requires an explicit protected runtime or guarded clone runner");
        }
        if (protectedEnvironment) {
            requireVerifyFullTls(uri);
        }
        if (appEnvironment.equals("preproduction")) {
            validatePreproductionTarget(environment, host, databaseName(uri));
        }
    }

    private static void requireVerifyFullTls(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new IllegalStateException(
                    "protected PostgreSQL requires sslmode=verify-full");
        }
        int matches = 0;
        String value = "";
        int rootCertificateMatches = 0;
        String rootCertificate = "";
        try {
            for (String pair : rawQuery.split("&", -1)) {
                String[] keyValue = pair.split("=", 2);
                String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                String normalizedKey = key.toLowerCase(Locale.ROOT);
                String decodedValue = keyValue.length == 2
                        ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "";
                if ("sslmode".equals(normalizedKey)) {
                    matches++;
                    value = decodedValue;
                } else if ("sslrootcert".equals(normalizedKey)) {
                    rootCertificateMatches++;
                    rootCertificate = decodedValue;
                } else if (normalizedKey.startsWith("ssl")) {
                    throw new IllegalStateException(
                            "protected PostgreSQL refuses alternate TLS override key: " + key);
                }
            }
        } catch (IllegalArgumentException malformedEncoding) {
            throw new IllegalStateException(
                    "protected PostgreSQL JDBC query is malformed", malformedEncoding);
        }
        if (matches != 1 || !"verify-full".equals(value)) {
            throw new IllegalStateException(
                    "protected PostgreSQL requires exactly one sslmode=verify-full");
        }
        validateOptionalRootCertificate(rootCertificateMatches, rootCertificate);
    }

    private static void validateOptionalRootCertificate(int matches, String configured) {
        if (matches == 0) return;
        String value = configured.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        if (matches != 1 || value.isEmpty() || normalized.contains("placeholder")
                || normalized.contains("replace-me") || normalized.contains("example")
                || normalized.matches("^[a-z][a-z0-9+.-]*://.*")) {
            throw new IllegalStateException(
                    "protected PostgreSQL sslrootcert must be one explicit local CA path or system");
        }
        if ("system".equals(normalized)) return;
        try {
            boolean remoteShare = value.startsWith("//") || value.startsWith("\\\\");
            boolean absoluteLocalPath = !remoteShare
                    && (value.startsWith("/") || Path.of(value).isAbsolute());
            if (!absoluteLocalPath) {
                throw new IllegalStateException(
                        "protected PostgreSQL sslrootcert must be an absolute local CA path");
            }
        } catch (InvalidPathException invalid) {
            throw new IllegalStateException(
                    "protected PostgreSQL sslrootcert path is invalid", invalid);
        }
    }

    /**
     * Refuse a production or undeclared clone target before Hikari/Flyway can
     * open the first socket. The bean-level runtime guard repeats these checks
     * against the connected database as defence in depth.
     */
    private static void validatePreproductionTarget(
            Environment environment, String host, String database) {
        String prefix = environment.getProperty(
                "app.migration-runner.enabled", Boolean.class, false)
                ? "app.migration-runner."
                : "app.preproduction-database.";
        if (!CLONE_CONFIRMATION.equals(required(
                environment, prefix + "database-confirmation"))) {
            throw new IllegalStateException(
                    "preproduction database confirmation does not match");
        }

        String productionHost = normalizeHost(required(
                environment, prefix + "production-host"));
        String productionDatabase = required(
                environment, prefix + "production-database");
        if (host.equals(productionHost) || database.equals(productionDatabase)) {
            throw new IllegalStateException(
                    "preproduction datasource target matches declared production");
        }

        Set<String> allowedHosts = csv(environment, prefix + "allowed-hosts", true);
        Set<String> allowedDatabases = csv(
                environment, prefix + "allowed-databases", false);
        if (allowedHosts.contains(productionHost)
                || allowedDatabases.contains(productionDatabase)) {
            throw new IllegalStateException(
                    "preproduction datasource allowlist contains declared production");
        }
        if (!allowedHosts.contains(host) || !allowedDatabases.contains(database)) {
            throw new IllegalStateException(
                    "preproduction datasource target is outside its exact allowlist");
        }

        String cloneKind = required(environment, prefix + "clone-kind")
                .toLowerCase(Locale.ROOT);
        String suffix = switch (cloneKind) {
            case "exact" -> "_exact";
            case "sanitized" -> "_staging";
            default -> throw new IllegalStateException(
                    "preproduction database clone kind must be exact or sanitized");
        };
        if (!database.endsWith(suffix)) {
            throw new IllegalStateException(
                    "preproduction datasource has the wrong clone-kind suffix");
        }
    }

    private static String databaseName(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.length() < 2 || path.indexOf('/', 1) >= 0) {
            throw new IllegalStateException(
                    "PostgreSQL JDBC target must contain exactly one database name");
        }
        String database = path.substring(1);
        if (!database.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException(
                    "PostgreSQL database name contains unsafe characters");
        }
        return database;
    }

    private static Set<String> csv(
            Environment environment, String property, boolean hosts) {
        String configured = required(environment, property);
        Set<String> values = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> hosts ? normalizeHost(value) : value)
                .collect(Collectors.toUnmodifiableSet());
        if (values.isEmpty() || values.stream().anyMatch(value ->
                value.contains("*") || value.contains("/") || value.contains("@"))) {
            throw new IllegalStateException(
                    "preproduction datasource allowlist must contain exact values");
        }
        return values;
    }

    private static String normalizeHost(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static String required(Environment environment, String property) {
        final String value;
        try {
            value = environment.getProperty(property, "").trim();
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            throw new IllegalStateException(
                    "preproduction datasource property could not be resolved: " + property,
                    unresolvedPlaceholder);
        }
        if (value.isBlank()) {
            throw new IllegalStateException(
                    "preproduction datasource property is empty: " + property);
        }
        return value;
    }

    private static void validateEnvironmentIdentity(
            Environment environment, String appEnvironment) {
        if (!SUPPORTED_APP_ENVIRONMENTS.contains(appEnvironment)) {
            throw new IllegalStateException(
                    "APP_ENV must exactly equal development, test, preproduction, or production");
        }
        String declaredAppEnvironment = environment.getProperty("APP_ENV", "").strip();
        if (!declaredAppEnvironment.isEmpty()
                && !declaredAppEnvironment.equals(appEnvironment)) {
            throw new IllegalStateException(
                    "APP_ENV and effective app.environment must exactly match");
        }
        boolean testIdentity = appEnvironment.equals("test")
                || Arrays.stream(environment.getActiveProfiles()).anyMatch("test"::equals);
        if (testIdentity && !isVerifiedTestRuntime()) {
            throw new IllegalStateException(
                    "the test environment/profile is restricted to the verified Gradle test runtime");
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
    }

    private static boolean isVerifiedTestRuntime() {
        if (!Boolean.getBoolean(VERIFIED_TEST_RUNTIME_PROPERTY)) {
            return false;
        }
        try {
            Class.forName(
                    "org.junit.jupiter.api.Test",
                    false,
                    DatabaseEnvironmentBoundary.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException notATestClasspath) {
            return false;
        }
    }
}

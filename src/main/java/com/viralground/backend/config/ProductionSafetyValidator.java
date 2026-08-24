package com.viralground.backend.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 운영 배포가 데모용 기본값으로 조용히 기동되는 것을 차단한다. 관리형 베타에서는
 * payments.gateway=disabled를 허용하지만 결제 API가 fail-closed로 동작해야 한다.
 */
@Component
public class ProductionSafetyValidator implements InitializingBean {
    private static final Set<String> SUPPORTED_APP_ENVIRONMENTS = Set.of(
            "development", "test", "preproduction", "production");
    private static final Set<String> PROTECTED_APP_ENVIRONMENTS = Set.of(
            "preproduction", "production");
    private static final Set<String> REJECTED_PROTECTED_ALIASES = Set.of(
            "prod", "staging", "pre-production");
    private static final Pattern RAILWAY_MANAGED_REDIS_HOST = Pattern.compile(
            "^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+railway\\.internal$");
    private static final String ADMIN_BOOTSTRAP_CONFIRMATION =
            "CREATE_ONE_PREPRODUCTION_ADMIN_ONCE";
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@([A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?\\.)+[A-Z]{2,63}$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> CONSUMER_EMAIL_DOMAINS = Set.of(
            "gmail.com", "naver.com", "daum.net", "kakao.com", "outlook.com",
            "hotmail.com", "yahoo.com", "icloud.com");

    private final Environment environment;

    public ProductionSafetyValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validateAppEnvironment();
        validateDatabaseEnvironmentBoundary();
        if (!isProtectedEnvironment()) return;

        validateInstagramProviderBoundary();
        validateMigrationRunnerMode();
        validateStagingMutationModes();
        if (isMigrationRunner()) {
            validateMigrationRunnerSafety();
            return;
        }
        if (isExactCompatibilityMode()) {
            validateExactCompatibilitySafety();
            return;
        }

        URI appUri = requireHttps("app.url");
        validateSentryContract();
        requireNonBlank("cors.allowed-origins");
        validateCorsOrigins(appUri);
        requireSecret("jwt.secret", 32);
        if (environment.getProperty("features.uploads.enabled", Boolean.class, false)) {
            requireHttps("files.public-base-url");
            requireSecret("files.signing-secret", 32);
            if (environment.getProperty("jwt.secret", "")
                    .equals(environment.getProperty("files.signing-secret", ""))) {
                throw new IllegalStateException("JWT_SECRET과 FILES_SIGNING_SECRET은 서로 다른 값을 사용해야 합니다.");
            }
        }
        String emailDeliveryMode = validateEmailDeliveryMode();
        if (!"disabled".equals(emailDeliveryMode)) {
            validateResendConfiguration(appUri);
        }
        validateDistributedRateLimit();
        if (!"validate".equalsIgnoreCase(environment.getProperty("spring.jpa.hibernate.ddl-auto", ""))) {
            throw new IllegalStateException("운영 Hibernate ddl-auto는 validate여야 합니다.");
        }
        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, false)) {
            throw new IllegalStateException("보호 환경에서는 Flyway schema validation을 활성화해야 합니다.");
        }
        requireFinalLegalVersion("legal.documents.terms-version");
        requireFinalLegalVersion("legal.documents.privacy-version");
        requireFinalLegalVersion("legal.documents.age14-version");
        requireFinalLegalVersion("legal.documents.creator-third-party-version");
        requireFinalLegalVersion("legal.documents.marketing-version");
        requireNonBlank("legal.privacy-officer.name");
        requireNonBlank("legal.privacy-officer.contact");

        reject("email.mock", "true", "운영에서는 EMAIL_MOCK=false여야 합니다.");
        reject("payments.gateway", "mock", "운영에서는 mock 결제 게이트웨이를 사용할 수 없습니다.");
        validateFeatureFlags();
        validateReleaseMetadata();
        validateSchedulingFlags();
        if (!environment.getProperty("auth.cookie.secure", Boolean.class, false)) {
            throw new IllegalStateException("운영 인증 쿠키는 AUTH_COOKIE_SECURE=true여야 합니다.");
        }
        validatePreproductionTopology(appUri);
        validateCookieDomain(appUri);

        String sameSite = environment.getProperty("auth.cookie.same-site", "Lax");
        if (!sameSite.equalsIgnoreCase("Lax") && !sameSite.equalsIgnoreCase("Strict")
                && !sameSite.equalsIgnoreCase("None")) {
            throw new IllegalStateException("auth.cookie.same-site는 Lax, Strict, None 중 하나여야 합니다.");
        }

        validateOptionalAdminBootstrap();
    }

    private boolean isProtectedEnvironment() {
        String appEnvironment = environment.getProperty("app.environment", "development");
        return PROTECTED_APP_ENVIRONMENTS.contains(appEnvironment)
                || Arrays.stream(environment.getActiveProfiles())
                .anyMatch(PROTECTED_APP_ENVIRONMENTS::contains);
    }

    private boolean isPreproduction() {
        String appEnvironment = environment.getProperty("app.environment", "development");
        return appEnvironment.equals("preproduction")
                || Arrays.stream(environment.getActiveProfiles())
                .anyMatch("preproduction"::equals);
    }

    private void validateAppEnvironment() {
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
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!protectedProfiles.isEmpty()
                && !protectedProfiles.equals(Set.of(appEnvironment))) {
            throw new IllegalStateException(
                    "APP_ENV and active protected Spring profile must exactly match");
        }
    }

    /**
     * Flyway may be disabled, so the datasource boundary must be enforced by an
     * unconditional startup bean before any CommandLineRunner can bootstrap rows.
     */
    private void validateDatabaseEnvironmentBoundary() {
        DatabaseEnvironmentBoundary.validate(environment);
    }

    private boolean isMigrationRunner() {
        return environment.getProperty("app.migration-runner.enabled", Boolean.class, false);
    }

    private boolean isExactCompatibilityMode() {
        return environment.getProperty("app.exact-compatibility.enabled", Boolean.class, false);
    }

    private void validateInstagramProviderBoundary() {
        String appEnvironment = environment.getProperty("app.environment", "").trim();
        String providerEnvironment = environment.getProperty(
                "instagram.environment", "").trim();
        if (!appEnvironment.equals(providerEnvironment)) {
            throw new IllegalStateException(
                    "INSTAGRAM_ENV must exactly match APP_ENV in a protected runtime");
        }
        String provider = environment.getProperty("instagram.provider", "")
                .trim().toLowerCase(Locale.ROOT);
        if (isMigrationRunner() || isExactCompatibilityMode()) {
            if (!"disabled".equals(provider)) {
                throw new IllegalStateException(
                        "guarded migration/exact compatibility requires INSTAGRAM_PROVIDER=disabled");
            }
        } else if (!"meta".equals(provider)) {
            throw new IllegalStateException(
                    "normal protected runtime requires INSTAGRAM_PROVIDER=meta for safe revocation");
        }
    }

    private void validateMigrationRunnerMode() {
        if (isMigrationRunner() && !isPreproduction()) {
            throw new IllegalStateException(
                    "guarded migration runner는 preproduction exact/sanitized clone에서만 사용할 수 있습니다.");
        }
        if (isExactCompatibilityMode() && (!isPreproduction() || isMigrationRunner())) {
            throw new IllegalStateException(
                    "exact compatibility mode는 preproduction에서 migration runner와 분리해 사용해야 합니다.");
        }
    }

    private void validateStagingMutationModes() {
        boolean provisioning = environment.getProperty(
                "app.staging.account-provisioning-enabled", Boolean.class, false);
        boolean e2e = environment.getProperty(
                "app.staging.e2e-mutation-enabled", Boolean.class, false);
        boolean emailValidation = environment.getProperty(
                "app.staging.email-validation-enabled", Boolean.class, false);
        String emailValidationRecipient = environment.getProperty(
                "app.staging.email-validation-recipient", "").trim();
        String allowedRaw = environment.getProperty(
                "app.staging.provisioning-allowed-emails", "");
        String beforeSeal = environment.getProperty(
                "app.preproduction-database.e2e-before-evidence-seal-sha256", "").trim();

        if (!isPreproduction()) {
            if (provisioning || e2e || emailValidation
                    || !emailValidationRecipient.isBlank()
                    || !allowedRaw.isBlank() || !beforeSeal.isBlank()) {
                throw new IllegalStateException(
                        "staging mutation-window controls are forbidden outside preproduction");
            }
            return;
        }
        if (isMigrationRunner() || isExactCompatibilityMode()) {
            if (provisioning || e2e || emailValidation
                    || !emailValidationRecipient.isBlank()
                    || !allowedRaw.isBlank() || !beforeSeal.isBlank()) {
                throw new IllegalStateException(
                        "migration runner/exact compatibility must disable staging mutation modes");
            }
            return;
        }
        int enabledModes = (provisioning ? 1 : 0) + (e2e ? 1 : 0)
                + (emailValidation ? 1 : 0);
        if (enabledModes > 1) {
            throw new IllegalStateException(
                    "staging provisioning, E2E, and email validation modes are mutually exclusive");
        }

        if (provisioning) {
            Set<String> allowedEmails = parseProvisioningAllowedEmails(allowedRaw);
            if (allowedEmails.isEmpty()) {
                throw new IllegalStateException(
                        "account provisioning requires STAGING_PROVISIONING_ALLOWED_EMAILS");
            }
            if (!"allowlist".equalsIgnoreCase(
                    environment.getProperty("email.delivery-mode", ""))) {
                throw new IllegalStateException(
                        "account provisioning requires EMAIL_DELIVERY_MODE=allowlist");
            }
            Set<String> deliveryRecipients = Arrays.stream(environment.getProperty(
                            "email.allowed-recipients", "").split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!deliveryRecipients.containsAll(allowedEmails)) {
                throw new IllegalStateException(
                        "every provisioning email must also be in EMAIL_ALLOWED_RECIPIENTS");
            }
            if (!beforeSeal.isBlank()) {
                throw new IllegalStateException(
                        "account provisioning must run before the E2E-before evidence seal exists");
            }
            String bootstrapEmail = environment.getProperty(
                    "admin.bootstrap.email", "").trim().toLowerCase(Locale.ROOT);
            if (environment.getProperty("admin.bootstrap.enabled", Boolean.class, false)
                    && !allowedEmails.contains(bootstrapEmail)) {
                throw new IllegalStateException(
                        "admin bootstrap email must be in STAGING_PROVISIONING_ALLOWED_EMAILS");
            }
            return;
        }

        if (!allowedRaw.isBlank()) {
            throw new IllegalStateException(
                    "STAGING_PROVISIONING_ALLOWED_EMAILS must be blank outside provisioning mode");
        }
        String emailMode = environment.getProperty(
                "email.delivery-mode", "").trim().toLowerCase(Locale.ROOT);
        if (emailValidation) {
            if (!"sanitized".equalsIgnoreCase(environment.getProperty(
                    "app.preproduction-database.clone-kind", ""))) {
                throw new IllegalStateException(
                        "staging email validation requires a normal sanitized runtime");
            }
            if (!beforeSeal.matches("(?i)^[0-9a-f]{64}$")) {
                throw new IllegalStateException(
                        "staging email validation requires a 64-hex E2E-before evidence seal");
            }
            if (!"allowlist".equalsIgnoreCase(
                    environment.getProperty("email.delivery-mode", ""))) {
                throw new IllegalStateException(
                        "staging email validation requires EMAIL_DELIVERY_MODE=allowlist");
            }
            if (!emailValidationRecipient.equals(
                    emailValidationRecipient.toLowerCase(Locale.ROOT))
                    || !EMAIL_ADDRESS.matcher(emailValidationRecipient).matches()
                    || emailValidationRecipient.contains("*")
                    || emailValidationRecipient.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalStateException(
                        "STAGING_EMAIL_VALIDATION_RECIPIENT requires one exact lowercase email address");
            }
            String emailValidationDomain = emailValidationRecipient.substring(
                    emailValidationRecipient.lastIndexOf('@') + 1);
            if (!"viralground.kr".equals(organizationalDomain(emailValidationDomain))) {
                throw new IllegalStateException(
                        "STAGING_EMAIL_VALIDATION_RECIPIENT must be an internal viralground.kr address");
            }
            Set<String> deliveryRecipients = Arrays.stream(environment.getProperty(
                            "email.allowed-recipients", "").split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!deliveryRecipients.contains(emailValidationRecipient)) {
                throw new IllegalStateException(
                        "STAGING_EMAIL_VALIDATION_RECIPIENT must be in EMAIL_ALLOWED_RECIPIENTS");
            }
            boolean outboxReady = environment.getProperty(
                    "notification.outbox.enabled", Boolean.class, false)
                    && environment.getProperty(
                    "notification.outbox.dispatch-enabled", Boolean.class, false)
                    && environment.getProperty(
                    "app.scheduling.enabled", Boolean.class, false);
            if (!outboxReady) {
                throw new IllegalStateException(
                        "staging email validation requires scheduling and outbox dispatch");
            }
            if (environment.getProperty("features.payments.enabled", Boolean.class, false)
                    || environment.getProperty("features.instagram.enabled", Boolean.class, false)
                    || environment.getProperty("features.uploads.enabled", Boolean.class, false)) {
                throw new IllegalStateException(
                        "staging email validation requires payments/Instagram/uploads disabled");
            }
            List<String> forbiddenJobs = List.of(
                    "instagram.sync.enabled",
                    "instagram.oauth-state.cleanup-enabled",
                    "instagram.webhook.cleanup-enabled",
                    "files.orphan-cleanup.enabled");
            if (forbiddenJobs.stream().anyMatch(job ->
                    environment.getProperty(job, Boolean.class, false))) {
                throw new IllegalStateException(
                        "staging email validation permits only notification outbox dispatch");
            }
            return;
        }
        if (!emailValidationRecipient.isBlank()) {
            throw new IllegalStateException(
                    "STAGING_EMAIL_VALIDATION_RECIPIENT must be blank outside email validation mode");
        }
        if (e2e) {
            if (!"disabled".equals(emailMode)) {
                throw new IllegalStateException(
                        "staging E2E mutation mode requires EMAIL_DELIVERY_MODE=disabled");
            }
            if (!beforeSeal.matches("(?i)^[0-9a-f]{64}$")) {
                throw new IllegalStateException(
                        "staging E2E mutation mode requires a 64-hex E2E-before evidence seal");
            }
        } else if (!beforeSeal.isBlank()) {
            throw new IllegalStateException(
                    "E2E-before evidence seal must be blank while staging E2E mutations are disabled");
        }
        if (enabledModes == 0 && !"disabled".equals(emailMode)) {
            throw new IllegalStateException(
                    "idle staging requires EMAIL_DELIVERY_MODE=disabled; use an explicit provisioning or email validation window");
        }
    }

    private Set<String> parseProvisioningAllowedEmails(String configured) {
        if (configured.isBlank()) return Set.of();
        List<String> values = Arrays.stream(configured.split(",", -1))
                .map(String::trim)
                .toList();
        if (values.stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException(
                    "STAGING_PROVISIONING_ALLOWED_EMAILS contains an empty entry");
        }
        for (String value : values) {
            if (!value.equals(value.toLowerCase(Locale.ROOT))
                    || !EMAIL_ADDRESS.matcher(value).matches()
                    || value.contains("*") || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalStateException(
                        "STAGING_PROVISIONING_ALLOWED_EMAILS requires exact lowercase email addresses");
            }
        }
        Set<String> unique = Set.copyOf(values);
        if (unique.size() != values.size()) {
            throw new IllegalStateException(
                    "STAGING_PROVISIONING_ALLOWED_EMAILS contains duplicate entries");
        }
        return unique;
    }

    private void validateMigrationRunnerSafety() {
        if (!"validate".equalsIgnoreCase(
                environment.getProperty("spring.jpa.hibernate.ddl-auto", ""))) {
            throw new IllegalStateException("guarded migration runner Hibernate ddl-auto는 validate여야 합니다.");
        }
        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, false)) {
            throw new IllegalStateException("guarded migration runner Flyway는 enabled여야 합니다.");
        }
        validateEmailDeliveryMode();
        if (!environment.getProperty("email.allowed-recipients", "").isBlank()) {
            throw new IllegalStateException(
                    "guarded migration runner EMAIL_ALLOWED_RECIPIENTS는 비워야 합니다.");
        }
        validateFeatureFlags();
        if (environment.getProperty("features.instagram.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "guarded migration runner Instagram 기능은 disabled여야 합니다.");
        }
        if (environment.getProperty("notification.outbox.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "guarded migration runner notification outbox는 disabled여야 합니다.");
        }
        validateReleaseMetadata();
        validateSchedulingFlags();
        if (environment.getProperty("app.scheduling.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "guarded migration runner APP_SCHEDULING_ENABLED는 false여야 합니다.");
        }
        validateAdminBootstrapDisabled("guarded migration runner");
    }

    private void validateExactCompatibilitySafety() {
        if (!"validate".equalsIgnoreCase(
                environment.getProperty("spring.jpa.hibernate.ddl-auto", ""))) {
            throw new IllegalStateException(
                    "exact compatibility Hibernate ddl-auto는 validate여야 합니다.");
        }
        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, false)
                || environment.getProperty(
                "spring.flyway.baseline-on-migrate", Boolean.class, false)) {
            throw new IllegalStateException(
                    "exact compatibility는 Flyway validate-only/baseline-disabled여야 합니다.");
        }
        validateEmailDeliveryMode();
        validateFeatureFlags();
        if (environment.getProperty("features.instagram.enabled", Boolean.class, false)
                || environment.getProperty("notification.outbox.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "exact compatibility에서는 Instagram/outbox를 비활성화해야 합니다.");
        }
        validateReleaseMetadata();
        validateSchedulingFlags();
        if (environment.getProperty("app.scheduling.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "exact compatibility에서는 모든 scheduler를 비활성화해야 합니다.");
        }
        validateAdminBootstrapDisabled("exact compatibility");
    }

    private void reject(String property, String forbidden, String message) {
        if (forbidden.equalsIgnoreCase(environment.getProperty(property, ""))) {
            throw new IllegalStateException(message);
        }
    }

    private void requireNonBlank(String property) {
        if (environment.getProperty(property, "").isBlank()) {
            throw new IllegalStateException("운영 필수 설정이 없습니다: " + property);
        }
    }

    private void requireSecret(String property, int minimumLength) {
        requireNonBlank(property);
        String value = environment.getProperty(property, "");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (value.length() < minimumLength
                || normalized.contains("your-")
                || normalized.contains("change-me")
                || normalized.contains("replace")
                || normalized.contains("example")) {
            throw new IllegalStateException("운영 비밀값이 너무 짧거나 예시값입니다: " + property);
        }
    }

    private void validateCorsOrigins(URI appUri) {
        List<String> origins = Arrays.stream(environment.getProperty("cors.allowed-origins", "").split(","))
                .map(value -> value.trim().replaceAll("^\"|\"$", ""))
                .filter(value -> !value.isBlank())
                .toList();
        if (origins.isEmpty()) {
            throw new IllegalStateException("운영 CORS origin이 없습니다.");
        }
        for (String origin : origins) {
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("운영 CORS origin 형식이 올바르지 않습니다.", e);
            }
            boolean hasOnlyOrigin = (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null && uri.getFragment() == null && uri.getUserInfo() == null;
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().equalsIgnoreCase("localhost") || origin.contains("*") || !hasOnlyOrigin) {
                throw new IllegalStateException("운영 CORS에는 공개 HTTPS origin만 사용할 수 있습니다.");
            }
        }
        String appOrigin = appUri.getScheme() + "://" + appUri.getAuthority();
        if (origins.stream().map(value -> value.endsWith("/")
                        ? value.substring(0, value.length() - 1) : value)
                .noneMatch(appOrigin::equalsIgnoreCase)) {
            throw new IllegalStateException("APP_URL origin이 CORS_ORIGINS에 포함되어야 합니다.");
        }
    }

    private void validateOptionalAdminBootstrap() {
        boolean enabled = environment.getProperty("admin.bootstrap.enabled", Boolean.class, false);
        String email = environment.getProperty("admin.bootstrap.email", "").trim();
        String password = environment.getProperty("admin.bootstrap.password", "");
        String name = environment.getProperty("admin.bootstrap.name", "").trim();
        String confirmation = environment.getProperty("admin.bootstrap.confirmation", "");
        boolean anyCredential = !email.isBlank() || !password.isBlank()
                || !name.isBlank() || !confirmation.isBlank();

        if (!isPreproduction()) {
            if (enabled || anyCredential) {
                throw new IllegalStateException(
                        "production에서는 admin bootstrap을 절대 사용할 수 없습니다.");
            }
            return;
        }

        if (enabled && !environment.getProperty(
                "app.staging.account-provisioning-enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "preproduction admin bootstrap is allowed only in explicit account provisioning mode");
        }

        if (!enabled) {
            if (anyCredential) {
                throw new IllegalStateException(
                        "비활성 admin bootstrap의 email/password/name/confirmation은 모두 비워야 합니다.");
            }
            return;
        }
        if (!ADMIN_BOOTSTRAP_CONFIRMATION.equals(confirmation)) {
            throw new IllegalStateException(
                    "preproduction admin bootstrap confirmation phrase가 일치하지 않습니다.");
        }
        if (!EMAIL_ADDRESS.matcher(email).matches() || name.isBlank()
                || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException(
                    "preproduction admin bootstrap email과 name을 완전하게 설정해야 합니다.");
        }
        String normalizedPassword = password.toLowerCase(Locale.ROOT);
        if (password.length() < 16 || password.length() > 72
                || containsPlaceholder(normalizedPassword)) {
            throw new IllegalStateException(
                    "preproduction admin bootstrap password는 16~72자의 실제 비밀값이어야 합니다.");
        }
    }

    private void validateAdminBootstrapDisabled(String context) {
        boolean enabled = environment.getProperty("admin.bootstrap.enabled", Boolean.class, false);
        boolean configured = !environment.getProperty("admin.bootstrap.email", "").isBlank()
                || !environment.getProperty("admin.bootstrap.password", "").isBlank()
                || !environment.getProperty("admin.bootstrap.name", "").isBlank()
                || !environment.getProperty("admin.bootstrap.confirmation", "").isBlank();
        if (enabled || configured) {
            throw new IllegalStateException(context + " admin bootstrap은 disabled/blank여야 합니다.");
        }
    }

    private void validateResendConfiguration(URI appUri) {
        String apiKey = environment.getProperty("resend.api-key", "").trim();
        String normalizedKey = apiKey.toLowerCase(Locale.ROOT);
        if (!apiKey.startsWith("re_") || apiKey.length() < 20
                || containsPlaceholder(normalizedKey)) {
            throw new IllegalStateException(
                    "운영 RESEND_API_KEY가 비어 있거나 placeholder/example 값입니다.");
        }

        String configuredFrom = environment.getProperty("resend.from", "").trim();
        if (configuredFrom.isBlank() || configuredFrom.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("운영 EMAIL_FROM(resend.from)이 필요합니다.");
        }
        String address = extractMailbox(configuredFrom);
        if (!EMAIL_ADDRESS.matcher(address).matches()) {
            throw new IllegalStateException("운영 EMAIL_FROM은 유효한 이메일 주소여야 합니다.");
        }

        String domain = address.substring(address.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        if (domain.equals("resend.dev") || domain.endsWith(".resend.dev")
                || domain.endsWith(".example") || domain.endsWith(".test")
                || domain.endsWith(".invalid") || domain.endsWith(".localhost")
                || domain.contains("placeholder") || domain.contains("yourdomain")
                || CONSUMER_EMAIL_DOMAINS.contains(domain)) {
            throw new IllegalStateException(
                    "운영 EMAIL_FROM은 Resend에서 인증한 회사 공개 도메인이어야 합니다.");
        }
        String appHost = appUri.getHost().toLowerCase(Locale.ROOT);
        boolean sameCompanyDomain = appHost.equals(domain)
                || appHost.endsWith("." + domain)
                || domain.endsWith("." + appHost)
                || organizationalDomain(appHost).equals(organizationalDomain(domain));
        if (!sameCompanyDomain) {
            throw new IllegalStateException(
                    "운영 EMAIL_FROM은 APP_URL과 같은 회사 도메인을 사용해야 합니다.");
        }
    }

    private String organizationalDomain(String host) {
        String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
        if (labels.length < 2) return host;
        return labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private String validateEmailDeliveryMode() {
        String mode = environment.getProperty("email.delivery-mode", "").trim().toLowerCase(Locale.ROOT);
        if (isMigrationRunner() || isExactCompatibilityMode()) {
            if (!"disabled".equals(mode)) {
                throw new IllegalStateException(
                        "guarded DB runner/compatibility EMAIL_DELIVERY_MODE은 disabled여야 합니다.");
            }
            validateDisabledEmailState();
            return mode;
        }

        if (isPreproduction()) {
            if (!"disabled".equals(mode) && !"allowlist".equals(mode)) {
                throw new IllegalStateException(
                        "사전운영 EMAIL_DELIVERY_MODE은 disabled 또는 allowlist여야 합니다.");
            }
        } else if (!"live".equals(mode)) {
            throw new IllegalStateException("운영 EMAIL_DELIVERY_MODE은 live여야 합니다.");
        }

        if ("disabled".equals(mode)) {
            validateDisabledEmailState();
            return mode;
        }
        if ("allowlist".equals(mode)) {
            List<String> recipients = Arrays.stream(
                            environment.getProperty("email.allowed-recipients", "").split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (recipients.isEmpty() || recipients.stream().anyMatch(value -> !EMAIL_ADDRESS.matcher(value).matches())) {
                throw new IllegalStateException(
                        "사전운영 EMAIL_ALLOWED_RECIPIENTS에는 유효한 내부 테스트 주소가 필요합니다.");
            }
            if (isPreproduction() && recipients.stream().anyMatch(value -> {
                String domain = value.substring(value.lastIndexOf('@') + 1)
                        .toLowerCase(Locale.ROOT);
                return !"viralground.kr".equals(organizationalDomain(domain));
            })) {
                throw new IllegalStateException(
                        "사전운영 EMAIL_ALLOWED_RECIPIENTS는 내부 viralground.kr 주소만 허용합니다.");
            }
        } else if (!environment.getProperty("email.allowed-recipients", "").isBlank()) {
            throw new IllegalStateException(
                    "EMAIL_DELIVERY_MODE=live이면 EMAIL_ALLOWED_RECIPIENTS를 비워야 합니다.");
        }
        validateActiveEmailOutbox(mode);
        return mode;
    }

    private void validateActiveEmailOutbox(String mode) {
        if (!"allowlist".equals(mode) && !"live".equals(mode)) return;
        if (!environment.getProperty("notification.outbox.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "allowlist/live email은 NOTIFICATION_OUTBOX_ENABLED=true가 필수입니다.");
        }
        if (!environment.getProperty("app.scheduling.enabled", Boolean.class, false)
                || !environment.getProperty(
                "notification.outbox.dispatch-enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "allowlist/live email은 global scheduling과 notification outbox dispatch가 모두 enabled여야 합니다.");
        }
    }

    private void validateDisabledEmailState() {
        if (!environment.getProperty("email.allowed-recipients", "").isBlank()) {
            throw new IllegalStateException(
                    "EMAIL_DELIVERY_MODE=disabled이면 EMAIL_ALLOWED_RECIPIENTS를 비워야 합니다.");
        }
        if (environment.getProperty("notification.outbox.dispatch-enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "EMAIL_DELIVERY_MODE=disabled이면 notification outbox dispatch를 비활성화해야 합니다.");
        }
        if (isPreproduction()
                && environment.getProperty("app.scheduling.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "사전운영 EMAIL_DELIVERY_MODE=disabled이면 global scheduling을 비활성화해야 합니다.");
        }
    }

    private void validateDistributedRateLimit() {
        if (!"redis".equalsIgnoreCase(environment.getProperty("rate-limit.backend", ""))) {
            throw new IllegalStateException("보호 환경에서는 RATE_LIMIT_BACKEND=redis가 필수입니다.");
        }
        String redisUrl = environment.getProperty("spring.data.redis.url", "").trim();
        URI uri;
        try {
            uri = URI.create(redisUrl);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("REDIS_URL 형식이 올바르지 않습니다.", invalid);
        }
        if (uri.getHost() == null || uri.getRawFragment() != null
                || (uri.getRawQuery() != null && !uri.getRawQuery().isBlank())) {
            throw new IllegalStateException("보호 환경 REDIS_URL 형식이 올바르지 않습니다.");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/") && !path.matches("/[0-9]+")) {
            throw new IllegalStateException("보호 환경 REDIS_URL database path가 올바르지 않습니다.");
        }

        String host = normalizeRedisHost(uri.getHost());
        String scheme = uri.getScheme() == null ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
        String transport = environment.getProperty(
                "rate-limit.redis-transport", "").trim().toLowerCase(Locale.ROOT);
        boolean railwayPrivateTransport = "redis".equals(scheme)
                && RAILWAY_MANAGED_REDIS_HOST.matcher(host).matches()
                && "railway-private".equals(transport);
        if (!"rediss".equals(scheme) && !railwayPrivateTransport) {
            throw new IllegalStateException(
                    "보호 환경 remote Redis는 rediss://를 사용해야 하며, Railway private redis://는 "
                            + "RATE_LIMIT_REDIS_TRANSPORT=railway-private가 필수입니다.");
        }
        if ("rediss".equals(scheme)
                && !transport.isBlank() && !"tls".equals(transport)) {
            throw new IllegalStateException(
                    "rediss:// REDIS_URL은 RATE_LIMIT_REDIS_TRANSPORT=tls를 사용해야 합니다.");
        }
        if (isPrivateRedisHost(host)) {
            throw new IllegalStateException("보호 환경 REDIS_URL은 loopback/private/link-local target을 거부합니다.");
        }
        Set<String> allowedHosts = redisAllowedHosts();
        if (!allowedHosts.contains(host)) {
            throw new IllegalStateException(
                    "보호 환경 REDIS_URL host는 REDIS_ALLOWED_HOSTS exact allowlist에 있어야 합니다.");
        }

        String appEnvironment = environment.getProperty("app.environment", "");
        String redisEnvironment = environment.getProperty(
                "rate-limit.redis-environment", "").trim();
        if (!appEnvironment.equals(redisEnvironment)) {
            throw new IllegalStateException(
                    "RATE_LIMIT_REDIS_ENVIRONMENT는 APP_ENV와 정확히 일치해야 합니다.");
        }
        String expectedPrefix = "viralground:" + appEnvironment + ":rate-limit";
        String configuredPrefix = environment.getProperty(
                "rate-limit.redis-key-prefix", "").trim();
        if (!expectedPrefix.equals(configuredPrefix)) {
            throw new IllegalStateException(
                    "RATE_LIMIT_REDIS_KEY_PREFIX는 환경별 고정값 "
                            + expectedPrefix + " 을(를) 사용해야 합니다.");
        }

        String userInfo = uri.getUserInfo();
        int separator = userInfo == null ? -1 : userInfo.indexOf(':');
        String password = separator < 0 ? "" : userInfo.substring(separator + 1);
        String normalizedPassword = password.toLowerCase(Locale.ROOT);
        if (password.length() < 16 || password.chars().anyMatch(Character::isISOControl)
                || containsPlaceholder(normalizedPassword)
                || Set.of("password", "secret", "redis").contains(normalizedPassword)) {
            throw new IllegalStateException(
                    "보호 환경 REDIS_URL에는 URL user-info의 실제 비밀번호가 필요합니다.");
        }
        if (!environment.getProperty("rate-limit.auth-fail-closed", Boolean.class, true)) {
            throw new IllegalStateException("보호 환경 인증 rate limit은 fail-closed여야 합니다.");
        }
    }

    private Set<String> redisAllowedHosts() {
        String configured = environment.getProperty("rate-limit.redis-allowed-hosts", "");
        if (configured.isBlank()) return Set.of();
        Set<String> hosts = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(ProductionSafetyValidator::normalizeRedisHost)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (hosts.isEmpty() || hosts.stream().anyMatch(value ->
                value.contains("*") || value.contains("/") || value.contains("@"))) {
            throw new IllegalStateException("REDIS_ALLOWED_HOSTS는 exact hostname 목록이어야 합니다.");
        }
        return hosts;
    }

    private static String normalizeRedisHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.endsWith(".")) {
            throw new IllegalStateException("REDIS host trailing dot is not allowed");
        }
        return normalized;
    }

    private static boolean isPrivateRedisHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".lan")
                || host.endsWith(".home")
                || (host.endsWith(".internal")
                && !RAILWAY_MANAGED_REDIS_HOST.matcher(host).matches())
                || !host.contains(".")) {
            return true;
        }
        if (host.matches("[0-9.]+")) {
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) return true;
            int[] values = new int[4];
            try {
                for (int i = 0; i < octets.length; i++) {
                    values[i] = Integer.parseInt(octets[i]);
                    if (values[i] < 0 || values[i] > 255) return true;
                }
            } catch (NumberFormatException invalid) {
                return true;
            }
            return values[0] == 0 || values[0] == 10 || values[0] == 127
                    || (values[0] == 100 && values[1] >= 64 && values[1] <= 127)
                    || (values[0] == 169 && values[1] == 254)
                    || (values[0] == 172 && values[1] >= 16 && values[1] <= 31)
                    || (values[0] == 192 && values[1] == 168)
                    || values[0] >= 224;
        }
        if (host.contains(":")) {
            try {
                InetAddress address = InetAddress.getByName(host);
                return address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress();
            } catch (UnknownHostException invalid) {
                return true;
            }
        }
        return false;
    }

    private void validateFeatureFlags() {
        if (environment.getProperty("demo.bootstrap.enabled", Boolean.class, false)) {
            throw new IllegalStateException("보호 환경에서는 DEMO_BOOTSTRAP_ENABLED=false여야 합니다.");
        }
        boolean paymentsEnabled = environment.getProperty("features.payments.enabled", Boolean.class, false);
        String gateway = environment.getProperty("payments.gateway", "disabled");
        if (paymentsEnabled || !"disabled".equalsIgnoreCase(gateway)) {
            throw new IllegalStateException(
                    "비거래형 첫 출시에서는 FEATURE_PAYMENTS_ENABLED=false 및 PAYMENTS_GATEWAY=disabled가 필수입니다.");
        }
        boolean uploadsEnabled = environment.getProperty("features.uploads.enabled", Boolean.class, false);
        String storage = environment.getProperty("files.storage", "").trim().toLowerCase(Locale.ROOT);
        if (uploadsEnabled && !"s3".equals(storage)) {
            throw new IllegalStateException(
                    "파일 업로드 활성화 시 영속 객체 저장소 FILES_STORAGE=s3가 필수입니다.");
        }
        if (!uploadsEnabled && !"disabled".equals(storage)) {
            throw new IllegalStateException(
                    "파일 업로드 비활성화 시 외부 객체 저장소 접근을 막기 위해 FILES_STORAGE=disabled가 필수입니다.");
        }
    }

    private void validateReleaseMetadata() {
        String releaseId = environment.getProperty("app.release-id", "").trim();
        String commitSha = environment.getProperty("app.git-commit-sha", "").trim();
        String buildTime = environment.getProperty("app.build-time", "").trim();
        if (releaseId.isBlank() || containsPlaceholder(releaseId.toLowerCase(Locale.ROOT))
                || releaseId.equalsIgnoreCase("local") || releaseId.equalsIgnoreCase("unknown")) {
            throw new IllegalStateException("보호 환경 RELEASE_ID는 실제 release candidate ID여야 합니다.");
        }
        if (!commitSha.matches("(?i)^[0-9a-f]{7,40}$")) {
            throw new IllegalStateException("보호 환경 GIT_COMMIT_SHA는 7~40자리 hexadecimal SHA여야 합니다.");
        }
        try {
            Instant.parse(buildTime);
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException("보호 환경 BUILD_TIME은 ISO-8601 UTC instant여야 합니다.", invalid);
        }
    }

    private void validateSchedulingFlags() {
        boolean global = environment.getProperty("app.scheduling.enabled", Boolean.class, false);
        List<String> jobs = List.of(
                "instagram.sync.enabled",
                "instagram.oauth-state.cleanup-enabled",
                "instagram.webhook.cleanup-enabled",
                "notification.outbox.dispatch-enabled",
                "files.orphan-cleanup.enabled");
        if (!global && jobs.stream().anyMatch(job ->
                environment.getProperty(job, Boolean.class, false))) {
            throw new IllegalStateException(
                    "APP_SCHEDULING_ENABLED=false이면 개별 scheduled job도 모두 false여야 합니다.");
        }
    }

    private void validateSentryContract() {
        String appEnvironment = environment.getProperty("app.environment", "").trim();
        String sentryEnvironment = environment.getProperty("sentry.environment", "").trim();
        if (!appEnvironment.equals(sentryEnvironment)) {
            throw new IllegalStateException(
                    "SENTRY_ENV must exactly match APP_ENV in a protected backend runtime");
        }

        String commitSha = environment.getProperty("app.git-commit-sha", "").trim();
        String sentryRelease = environment.getProperty("sentry.release", "").trim();
        if (!commitSha.matches("^[0-9a-f]{40}$") || !commitSha.equals(sentryRelease)) {
            throw new IllegalStateException(
                    "SENTRY_RELEASE must exactly equal the full lowercase backend GIT_COMMIT_SHA");
        }

        String approvedHost = environment.getProperty(
                "sentry.approved-host", "").trim();
        String approvedProjectId = environment.getProperty(
                "sentry.approved-project-id", "").trim();
        if (!approvedHost.equals(approvedHost.toLowerCase(Locale.ROOT))
                || !approvedHost.matches(
                "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")
                || containsPlaceholder(approvedHost)
                || !approvedProjectId.matches("^[A-Za-z0-9_-]{1,128}$")
                || containsPlaceholder(approvedProjectId.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "approved backend Sentry host/project identity is missing or invalid");
        }

        URI dsn = requireHttps("sentry.dsn");
        String rawUserInfo = dsn.getRawUserInfo();
        boolean canonicalIdentity = approvedHost.equalsIgnoreCase(dsn.getHost())
                && dsn.getPort() == -1
                && ("/" + approvedProjectId).equals(dsn.getRawPath())
                && dsn.getRawQuery() == null
                && dsn.getRawFragment() == null
                && rawUserInfo != null
                && rawUserInfo.matches("^[A-Za-z0-9_-]{1,256}$");
        if (!canonicalIdentity) {
            throw new IllegalStateException(
                    "backend Sentry DSN does not match the approved host/project identity");
        }
    }

    private void validatePreproductionTopology(URI appUri) {
        if (!isPreproduction()) return;

        String expectedOrigin = "https://staging.viralground.kr";
        String path = appUri.getRawPath();
        if (!"staging.viralground.kr".equalsIgnoreCase(appUri.getHost())
                || appUri.getPort() != -1
                || (path != null && !path.isEmpty() && !"/".equals(path))
                || appUri.getRawQuery() != null
                || appUri.getRawFragment() != null
                || appUri.getRawUserInfo() != null) {
            throw new IllegalStateException(
                    "사전운영 APP_URL은 https://staging.viralground.kr이어야 합니다.");
        }

        List<String> origins = Arrays.stream(
                        environment.getProperty("cors.allowed-origins", "").split(","))
                .map(value -> value.trim().replaceAll("^\"|\"$", ""))
                .filter(value -> !value.isBlank())
                .toList();
        if (origins.size() != 1 || !expectedOrigin.equalsIgnoreCase(origins.get(0))) {
            throw new IllegalStateException(
                    "사전운영 CORS는 staging frontend origin 하나만 허용해야 합니다.");
        }

        String cookieDomain = environment.getProperty("auth.cookie.domain", "")
                .trim().toLowerCase(Locale.ROOT);
        if (!".staging.viralground.kr".equals(cookieDomain)) {
            throw new IllegalStateException(
                    "사전운영 AUTH_COOKIE_DOMAIN은 .staging.viralground.kr로 격리해야 합니다.");
        }
    }

    private String extractMailbox(String configuredFrom) {
        int opening = configuredFrom.indexOf('<');
        int closing = configuredFrom.indexOf('>');
        if (opening < 0 && closing < 0) return configuredFrom;
        if (opening <= 0 || closing != configuredFrom.length() - 1 || opening >= closing
                || configuredFrom.indexOf('<', opening + 1) >= 0
                || configuredFrom.indexOf('>', closing + 1) >= 0) {
            throw new IllegalStateException("운영 EMAIL_FROM 표시 이름 형식이 올바르지 않습니다.");
        }
        return configuredFrom.substring(opening + 1, closing).trim();
    }

    private boolean containsPlaceholder(String normalizedValue) {
        return normalizedValue.contains("example")
                || normalizedValue.contains("placeholder")
                || normalizedValue.contains("your-")
                || normalizedValue.contains("change-me")
                || normalizedValue.contains("replace")
                || normalizedValue.contains("xxxxxxxx");
    }

    private void validateCookieDomain(URI appUri) {
        String configured = environment.getProperty("auth.cookie.domain", "").trim().toLowerCase(Locale.ROOT);
        if (configured.isBlank()) {
            throw new IllegalStateException("운영 AUTH_COOKIE_DOMAIN은 프런트/API가 공유하는 상위 도메인이어야 합니다.");
        }
        String domain = configured.startsWith(".") ? configured.substring(1) : configured;
        if (domain.isBlank() || domain.equals("localhost") || domain.contains("yourdomain")
                || domain.endsWith(".example")) {
            throw new IllegalStateException("운영 AUTH_COOKIE_DOMAIN이 예시값이거나 공개 도메인이 아닙니다.");
        }
        String appHost = appUri.getHost().toLowerCase(Locale.ROOT);
        if (!appHost.equals(domain) && !appHost.endsWith("." + domain)) {
            throw new IllegalStateException("AUTH_COOKIE_DOMAIN은 APP_URL의 상위 도메인이어야 합니다.");
        }
    }

    private void requireFinalLegalVersion(String property) {
        String value = environment.getProperty(property, "");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalStateException("운영 최종 법적 문서 버전이 없습니다: " + property);
        }
        for (String forbidden : new String[]{
                "draft", "placeholder", "todo", "tbd", "sample", "example", "replace", "초안"
        }) {
            if (normalized.contains(forbidden)) {
                throw new IllegalStateException("운영 법적 문서 버전에 초안/placeholder를 사용할 수 없습니다: "
                        + property);
            }
        }
    }

    private URI requireHttps(String property) {
        requireNonBlank(property);
        URI uri;
        try {
            uri = URI.create(environment.getRequiredProperty(property));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("운영 URL 형식이 올바르지 않습니다: " + property, e);
        }
        String raw = environment.getRequiredProperty(property).toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getHost().equalsIgnoreCase("localhost")
                || raw.contains("yourdomain") || raw.contains("your-")
                || raw.contains("xxxxxxxx")) {
            throw new IllegalStateException("운영 URL은 공개 HTTPS 주소여야 합니다: " + property);
        }
        return uri;
    }
}

package com.viralground.backend.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
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
        if (!isProduction()) return;

        URI appUri = requireHttps("app.url");
        requireHttps("files.public-base-url");
        requireHttps("sentry.dsn");
        requireNonBlank("cors.allowed-origins");
        validateCorsOrigins(appUri);
        requireSecret("jwt.secret", 32);
        requireSecret("files.signing-secret", 32);
        if (environment.getProperty("jwt.secret", "")
                .equals(environment.getProperty("files.signing-secret", ""))) {
            throw new IllegalStateException("JWT_SECRET과 FILES_SIGNING_SECRET은 서로 다른 값을 사용해야 합니다.");
        }
        validateResendConfiguration(appUri);
        if (!"validate".equalsIgnoreCase(environment.getProperty("spring.jpa.hibernate.ddl-auto", ""))) {
            throw new IllegalStateException("운영 Hibernate ddl-auto는 validate여야 합니다.");
        }
        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, false)) {
            throw new IllegalStateException("운영에서는 Flyway migration을 활성화해야 합니다.");
        }
        requireFinalLegalVersion("legal.documents.terms-version");
        requireFinalLegalVersion("legal.documents.privacy-version");
        requireFinalLegalVersion("legal.documents.age14-version");
        requireFinalLegalVersion("legal.documents.creator-third-party-version");
        requireFinalLegalVersion("legal.documents.marketing-version");
        requireNonBlank("legal.privacy-officer.name");
        requireNonBlank("legal.privacy-officer.contact");

        reject("email.mock", "true", "운영에서는 EMAIL_MOCK=false여야 합니다.");
        reject("files.storage", "local", "운영에서는 영속 객체 저장소를 사용해야 합니다.");
        reject("payments.gateway", "mock", "운영에서는 mock 결제 게이트웨이를 사용할 수 없습니다.");
        if (!environment.getProperty("auth.cookie.secure", Boolean.class, false)) {
            throw new IllegalStateException("운영 인증 쿠키는 AUTH_COOKIE_SECURE=true여야 합니다.");
        }
        validateCookieDomain(appUri);

        String sameSite = environment.getProperty("auth.cookie.same-site", "Lax");
        if (!sameSite.equalsIgnoreCase("Lax") && !sameSite.equalsIgnoreCase("Strict")
                && !sameSite.equalsIgnoreCase("None")) {
            throw new IllegalStateException("auth.cookie.same-site는 Lax, Strict, None 중 하나여야 합니다.");
        }

        validateOptionalAdminBootstrap();
    }

    private boolean isProduction() {
        String appEnvironment = environment.getProperty("app.environment", "").toLowerCase(Locale.ROOT);
        return appEnvironment.equals("production") || appEnvironment.equals("prod")
                || Arrays.stream(environment.getActiveProfiles())
                .anyMatch(v -> v.equalsIgnoreCase("production") || v.equalsIgnoreCase("prod"));
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
        String email = environment.getProperty("admin.bootstrap.email", "").trim();
        String password = environment.getProperty("admin.bootstrap.password", "");
        if (email.isBlank() && password.isBlank()) {
            return;
        }
        if (email.isBlank() || password.isBlank()) {
            throw new IllegalStateException("관리자 bootstrap email과 password는 함께 설정해야 합니다.");
        }
        if (password.length() < 12) {
            throw new IllegalStateException("관리자 bootstrap password는 12자 이상이어야 합니다.");
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
                || domain.endsWith("." + appHost);
        if (!sameCompanyDomain) {
            throw new IllegalStateException(
                    "운영 EMAIL_FROM은 APP_URL과 같은 회사 도메인을 사용해야 합니다.");
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

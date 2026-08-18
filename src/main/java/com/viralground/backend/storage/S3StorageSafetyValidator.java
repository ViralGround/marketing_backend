package com.viralground.backend.storage;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "files.storage", havingValue = "s3")
public class S3StorageSafetyValidator implements InitializingBean {

    private static final Set<String> CREDENTIAL_MODES = Set.of("static", "default-chain");
    private static final Pattern BUCKET = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{1,253}[A-Za-z0-9]$");
    private static final long MAX_PRESIGN_TTL_SECONDS = Duration.ofDays(7).toSeconds();

    private final FileStorageProperties properties;
    private final Environment environment;

    public S3StorageSafetyValidator(FileStorageProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    void validate() {
        FileStorageProperties.S3 s3 = properties.getS3();
        requireText(s3.getRegion(), "files.s3.region");
        requireText(s3.getBucket(), "files.s3.bucket");
        if (!BUCKET.matcher(s3.getBucket()).matches()) {
            throw new IllegalStateException("files.s3.bucket 형식이 올바르지 않습니다.");
        }

        String mode = normalizedMode(s3);
        if (!CREDENTIAL_MODES.contains(mode)) {
            throw new IllegalStateException(
                    "files.s3.credentials-mode는 static 또는 default-chain이어야 합니다.");
        }
        if (mode.equals("static")) {
            requireText(s3.getAccessKey(), "files.s3.access-key");
            requireText(s3.getSecretKey(), "files.s3.secret-key");
        }

        long ttl = properties.getSigningTtlSeconds();
        if (ttl < 1 || ttl > MAX_PRESIGN_TTL_SECONDS) {
            throw new IllegalStateException("files.signing-ttl-seconds는 1초~7일 사이여야 합니다.");
        }
        if (properties.getMaxSizeBytes() <= 0 || properties.getMaxImageSizeBytes() <= 0) {
            throw new IllegalStateException("업로드 최대 용량은 0보다 커야 합니다.");
        }
        requireBoundedDuration(s3.getConnectTimeout(), Duration.ofSeconds(30),
                "files.s3.connect-timeout");
        requireBoundedDuration(s3.getReadTimeout(), Duration.ofSeconds(60),
                "files.s3.read-timeout");
        validateEndpoint(s3.getEndpoint());
    }

    String normalizedMode() {
        return normalizedMode(properties.getS3());
    }

    private static String normalizedMode(FileStorageProperties.S3 s3) {
        return s3.getCredentialsMode() == null
                ? "" : s3.getCredentialsMode().trim().toLowerCase(Locale.ROOT);
    }

    private void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return;
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("files.s3.endpoint URL 형식이 올바르지 않습니다.", e);
        }
        boolean supportedScheme = "https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme());
        if (!supportedScheme || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "files.s3.endpoint는 자격증명·쿼리·프래그먼트 없는 HTTP(S) URL이어야 합니다.");
        }
        if (isProduction()) {
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalStateException("운영 files.s3.endpoint는 HTTPS여야 합니다.");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
                throw new IllegalStateException("운영 files.s3.endpoint에 loopback host를 사용할 수 없습니다.");
            }
        }
    }

    private boolean isProduction() {
        String appEnvironment = environment.getProperty("app.environment", "")
                .toLowerCase(Locale.ROOT);
        return appEnvironment.equals("production") || appEnvironment.equals("prod")
                || Arrays.stream(environment.getActiveProfiles())
                .anyMatch(v -> v.equalsIgnoreCase("production") || v.equalsIgnoreCase("prod"));
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("필수 객체 저장소 설정이 없습니다: " + property);
        }
    }

    private static void requireBoundedDuration(Duration value, Duration maximum, String property) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalStateException(
                    property + "는 0보다 크고 " + maximum.toSeconds() + "초 이하여야 합니다.");
        }
    }
}

package com.viralground.backend.storage;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
    private final HostAddressResolver hostAddressResolver;

    public S3StorageSafetyValidator(FileStorageProperties properties, Environment environment) {
        this(properties, environment, InetAddress::getAllByName);
    }

    S3StorageSafetyValidator(FileStorageProperties properties, Environment environment,
                             HostAddressResolver hostAddressResolver) {
        this.properties = properties;
        this.environment = environment;
        this.hostAddressResolver = hostAddressResolver;
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
        validateProtectedBucketIsolation(s3);
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
        ProtectedEnvironment protectedEnvironment = protectedEnvironment();
        if (protectedEnvironment != ProtectedEnvironment.NONE) {
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalStateException("보호 환경 files.s3.endpoint는 HTTPS여야 합니다.");
            }
            validatePublicEndpointHost(uri.getHost());
        }
    }

    private void validateProtectedBucketIsolation(FileStorageProperties.S3 s3) {
        ProtectedEnvironment protectedEnvironment = protectedEnvironment();
        if (protectedEnvironment == ProtectedEnvironment.NONE) return;

        Set<String> stagingBuckets = normalizedBucketSet(s3.getStagingAllowedBuckets(),
                "files.s3.staging-allowed-buckets");
        Set<String> productionBuckets = normalizedBucketSet(s3.getProductionAllowedBuckets(),
                "files.s3.production-allowed-buckets");
        if (stagingBuckets.isEmpty() || productionBuckets.isEmpty()) {
            throw new IllegalStateException(
                    "보호 환경은 staging/production S3 bucket allowlist를 모두 명시해야 합니다.");
        }
        Set<String> overlap = new LinkedHashSet<>(stagingBuckets);
        overlap.retainAll(productionBuckets);
        if (!overlap.isEmpty()) {
            throw new IllegalStateException(
                    "staging과 production S3 bucket allowlist는 서로 겹칠 수 없습니다.");
        }

        String bucket = s3.getBucket().trim().toLowerCase(Locale.ROOT);
        Set<String> allowed = protectedEnvironment == ProtectedEnvironment.STAGING
                ? stagingBuckets : productionBuckets;
        Set<String> denied = protectedEnvironment == ProtectedEnvironment.STAGING
                ? productionBuckets : stagingBuckets;
        String environmentName = protectedEnvironment == ProtectedEnvironment.STAGING
                ? "staging" : "production";
        if (denied.contains(bucket)) {
            throw new IllegalStateException(
                    environmentName + "에서 상대 환경 전용 S3 bucket을 공유할 수 없습니다.");
        }
        if (!allowed.contains(bucket)) {
            throw new IllegalStateException(
                    "files.s3.bucket이 " + environmentName + " exact allowlist에 없습니다.");
        }
    }

    private Set<String> normalizedBucketSet(Iterable<String> values, String property) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String value : values) {
            if (value == null) continue;
            for (String item : value.split(",")) {
                String bucket = item.trim().toLowerCase(Locale.ROOT);
                if (bucket.isEmpty()) continue;
                if (!BUCKET.matcher(bucket).matches()) {
                    throw new IllegalStateException(property + "에 잘못된 bucket 이름이 있습니다.");
                }
                result.add(bucket);
            }
        }
        return result;
    }

    private void validatePublicEndpointHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")
                || host.endsWith(".lan") || host.endsWith(".home")
                || host.endsWith(".test") || host.endsWith(".invalid")
                || host.endsWith(".example")) {
            throw new IllegalStateException(
                    "보호 환경 files.s3.endpoint에 loopback/private host를 사용할 수 없습니다.");
        }

        final InetAddress[] addresses;
        try {
            addresses = hostAddressResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(
                    "보호 환경 files.s3.endpoint host를 DNS로 확인할 수 없습니다.", e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalStateException(
                    "보호 환경 files.s3.endpoint host의 DNS 주소가 없습니다.");
        }
        for (InetAddress address : addresses) {
            if (isNonPublicAddress(address)) {
                throw new IllegalStateException(
                        "보호 환경 files.s3.endpoint가 loopback/private 주소를 가리킵니다.");
            }
        }
    }

    private static boolean isNonPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && third == 0)
                    || (first == 192 && second == 0 && third == 2)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 224;
        }
        if (bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            return uniqueLocal || documentation;
        }
        return true;
    }

    private ProtectedEnvironment protectedEnvironment() {
        String appEnvironment = environment.getProperty("app.environment", "")
                .trim().toLowerCase(Locale.ROOT);
        if (appEnvironment.equals("production") || appEnvironment.equals("prod")
                || hasActiveProfile("production", "prod")) {
            return ProtectedEnvironment.PRODUCTION;
        }
        if (appEnvironment.equals("preproduction") || appEnvironment.equals("preprod")
                || appEnvironment.equals("staging")
                || hasActiveProfile("preproduction", "preprod", "staging")) {
            return ProtectedEnvironment.STAGING;
        }
        return ProtectedEnvironment.NONE;
    }

    private boolean hasActiveProfile(String... profiles) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(active -> Arrays.stream(profiles)
                        .anyMatch(active::equalsIgnoreCase));
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

    private enum ProtectedEnvironment {
        NONE, STAGING, PRODUCTION
    }

    @FunctionalInterface
    interface HostAddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}

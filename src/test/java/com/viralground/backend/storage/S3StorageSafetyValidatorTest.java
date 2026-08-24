package com.viralground.backend.storage;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3StorageSafetyValidatorTest {

    @Test
    void acceptsProductionR2StaticCredentialsOverHttps() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setEndpoint("https://account.r2.cloudflarestorage.com");
        MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "production");

        assertThatCode(() -> new S3StorageSafetyValidator(properties, environment, publicResolver())
                .afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void acceptsPreproductionDedicatedBucketAndPublicHttpsEndpoint() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("viralground-staging");
        properties.getS3().setEndpoint("https://account.r2.cloudflarestorage.com");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction");

        assertThatCode(() -> new S3StorageSafetyValidator(
                properties, environment, publicResolver()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAwsSdkDefaultEndpointInStaging() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("viralground-staging");
        properties.getS3().setEndpoint("");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "staging");

        assertThatCode(() -> new S3StorageSafetyValidator(properties, environment,
                host -> { throw new AssertionError("AWS SDK default endpoint must not resolve custom host"); })
                .afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void acceptsDefaultChainWithoutStaticSecrets() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setCredentialsMode("default-chain");
        properties.getS3().setAccessKey("");
        properties.getS3().setSecretKey("");

        assertThatCode(() -> new S3StorageSafetyValidator(
                properties, new MockEnvironment()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingBucket() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(
                properties, new MockEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("files.s3.bucket");
    }

    @Test
    void rejectsHttpEndpointInProduction() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setEndpoint("http://object-store.internal:9000");
        MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "production");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsHttpEndpointInPreproduction() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("viralground-staging");
        properties.getS3().setEndpoint("http://objects.vendor.example.net");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(
                properties, environment, publicResolver()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsLoopbackEndpointInProduction() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setEndpoint("https://localhost:9000");
        MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "production");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void rejectsEndpointResolvingToPrivateAddressInStaging() throws Exception {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("viralground-staging");
        properties.getS3().setEndpoint("https://objects.vendor.example.net");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "staging");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment,
                host -> new InetAddress[]{InetAddress.getByName("10.20.30.40")})
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private");
    }

    @Test
    void rejectsUnresolvableEndpointInPreproduction() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("viralground-staging");
        properties.getS3().setEndpoint("https://objects.vendor.example.net");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment,
                host -> { throw new java.net.UnknownHostException(host); })
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DNS");
    }

    @Test
    void rejectsProductionBucketSharingInStaging() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setEndpoint("");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "staging");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공유");
    }

    @Test
    void rejectsStagingBucketSharingInProduction() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("viralground-staging");
        properties.getS3().setEndpoint("");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공유");
    }

    @Test
    void rejectsBucketOutsideStagingExactAllowlist() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("viralground-staging-typo");
        properties.getS3().setEndpoint("");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact allowlist");
    }

    @Test
    void rejectsOverlappingStagingAndProductionAllowlists() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("viralground-staging");
        properties.getS3().setStagingAllowedBuckets(List.of("viralground-staging"));
        properties.getS3().setProductionAllowedBuckets(
                List.of("viralground-production", "viralground-staging"));
        properties.getS3().setEndpoint("");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "staging");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("겹칠 수 없습니다");
    }

    @Test
    void rejectsMissingExplicitBucketPolicyInProtectedEnvironment() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setStagingAllowedBuckets(List.of());
        properties.getS3().setEndpoint("");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("모두 명시");
    }

    @Test
    void activeStagingProfileAlsoEnforcesIsolation() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("viralground-production");
        properties.getS3().setEndpoint("");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공유");
    }

    @Test
    void trimmedCaseInsensitivePreproductionEnvironmentStillEnforcesIsolation() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setBucket("viralground-production");
        properties.getS3().setEndpoint("");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", " PreProduction ");

        assertThatThrownBy(() -> new S3StorageSafetyValidator(properties, environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공유");
    }

    @Test
    void rejectsPresignTtlLongerThanSevenDays() {
        FileStorageProperties properties = validProperties();
        properties.setSigningTtlSeconds(Duration.ofDays(7).plusSeconds(1).toSeconds());

        assertThatThrownBy(() -> new S3StorageSafetyValidator(
                properties, new MockEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7일");
    }

    @Test
    void rejectsUnboundedReadTimeout() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setReadTimeout(Duration.ofMinutes(5));

        assertThatThrownBy(() -> new S3StorageSafetyValidator(
                properties, new MockEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-timeout");
    }

    private static FileStorageProperties validProperties() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.getS3().setRegion("auto");
        properties.getS3().setBucket("viralground-production");
        properties.getS3().setStagingAllowedBuckets(List.of("viralground-staging"));
        properties.getS3().setProductionAllowedBuckets(List.of("viralground-production"));
        properties.getS3().setCredentialsMode("static");
        properties.getS3().setAccessKey("test-access-key");
        properties.getS3().setSecretKey("test-secret-key");
        return properties;
    }

    private static S3StorageSafetyValidator.HostAddressResolver publicResolver() {
        return host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};
    }
}

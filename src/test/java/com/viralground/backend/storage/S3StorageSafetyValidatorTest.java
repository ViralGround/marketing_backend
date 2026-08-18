package com.viralground.backend.storage;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3StorageSafetyValidatorTest {

    @Test
    void acceptsProductionR2StaticCredentialsOverHttps() {
        FileStorageProperties properties = validProperties();
        properties.getS3().setEndpoint("https://account.r2.cloudflarestorage.com");
        MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "production");

        assertThatCode(() -> new S3StorageSafetyValidator(properties, environment)
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
        properties.getS3().setCredentialsMode("static");
        properties.getS3().setAccessKey("test-access-key");
        properties.getS3().setSecretKey("test-secret-key");
        return properties;
    }
}

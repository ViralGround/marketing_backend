package com.viralground.backend.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "files.storage", havingValue = "s3")
public class S3StorageConfiguration {

    @Bean
    AwsCredentialsProvider fileStorageCredentialsProvider(
            FileStorageProperties properties, S3StorageSafetyValidator validator) {
        FileStorageProperties.S3 s3 = properties.getS3();
        if (validator.normalizedMode().equals("default-chain")) {
            return DefaultCredentialsProvider.create();
        }
        if (s3.getSessionToken() != null && !s3.getSessionToken().isBlank()) {
            return StaticCredentialsProvider.create(AwsSessionCredentials.create(
                    s3.getAccessKey(), s3.getSecretKey(), s3.getSessionToken()));
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                s3.getAccessKey(), s3.getSecretKey()));
    }

    @Bean
    S3Configuration fileStorageS3ServiceConfiguration(
            FileStorageProperties properties, S3StorageSafetyValidator validator) {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(properties.getS3().isPathStyle())
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Client fileStorageS3Client(FileStorageProperties properties,
                                 AwsCredentialsProvider fileStorageCredentialsProvider,
                                 S3Configuration fileStorageS3ServiceConfiguration,
                                 S3StorageSafetyValidator validator) {
        FileStorageProperties.S3 s3 = properties.getS3();
        ClientOverrideConfiguration override = ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(s3.getReadTimeout())
                .apiCallTimeout(s3.getReadTimeout().multipliedBy(3))
                .retryStrategy(RetryMode.STANDARD)
                .build();
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(fileStorageCredentialsProvider)
                .serviceConfiguration(fileStorageS3ServiceConfiguration)
                .overrideConfiguration(override)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(s3.getConnectTimeout())
                        .socketTimeout(s3.getReadTimeout()));
        applyEndpoint(builder, s3.getEndpoint());
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner fileStorageS3Presigner(FileStorageProperties properties,
                                       AwsCredentialsProvider fileStorageCredentialsProvider,
                                       S3Configuration fileStorageS3ServiceConfiguration,
                                       S3StorageSafetyValidator validator) {
        FileStorageProperties.S3 s3 = properties.getS3();
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(fileStorageCredentialsProvider)
                .serviceConfiguration(fileStorageS3ServiceConfiguration);
        if (s3.getEndpoint() != null && !s3.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(s3.getEndpoint().trim()));
        }
        return builder.build();
    }

    private static void applyEndpoint(S3ClientBuilder builder, String endpoint) {
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.trim()));
        }
    }
}

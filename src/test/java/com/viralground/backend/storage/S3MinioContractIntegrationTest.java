package com.viralground.backend.storage;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
import com.viralground.backend.dto.file.UploadCompletionResponse;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/** Real S3-compatible contract using an isolated, disposable MinIO Testcontainer. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class S3MinioContractIntegrationTest {

    private static final String ACCESS_KEY = "minio-contract-admin";
    private static final String SECRET_KEY = "minio-contract-only-not-secret";
    private static final String BUCKET = "viralground-contract-test";
    private static final long DEFAULT_SIGNING_TTL_SECONDS = 300;

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withReuse(false)
            .waitingFor(Wait.forHttp("/minio/health/live")
                    .forPort(9000)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static S3Client s3Client;
    private static S3Presigner presigner;
    private static S3FileStorage storage;
    private static FileStorageProperties properties;
    private static UploadOwnershipService ownershipService;

    @BeforeAll
    static void setUpContract() {
        URI endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
        var s3Configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Configuration)
                .build();
        presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Configuration)
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());

        properties = new FileStorageProperties();
        properties.getS3().setBucket(BUCKET);
        properties.setSigningTtlSeconds(DEFAULT_SIGNING_TTL_SECONDS);
        properties.setMaxSizeBytes(1_048_576);
        properties.setAllowedContentTypes(List.of("video/mp4"));

        UploadRecordRepository repository = Mockito.mock(UploadRecordRepository.class);
        AtomicReference<UploadRecord> registeredUpload = new AtomicReference<>();
        when(repository.save(any(UploadRecord.class))).thenAnswer(invocation -> {
            UploadRecord saved = invocation.getArgument(0);
            registeredUpload.set(saved);
            return saved;
        });
        when(repository.findByFileKeyForUpdate(anyString())).thenAnswer(invocation -> {
            UploadRecord saved = registeredUpload.get();
            return saved != null && saved.getFileKey().equals(invocation.getArgument(0))
                    ? Optional.of(saved) : Optional.empty();
        });

        Clock clock = Clock.systemUTC();
        storage = new S3FileStorage(properties, s3Client, presigner, repository, clock);
        ownershipService = new UploadOwnershipService(repository, storage, clock);
    }

    @AfterAll
    static void closeClients() {
        if (presigner != null) presigner.close();
        if (s3Client != null) s3Client.close();
    }

    @Test
    void presignPutHeadGetAndDeleteAgainstMinio() throws Exception {
        byte[] payload = bytes("synthetic-video-payload");
        PresignedUpload upload = storage.presignUpload("video/mp4", payload.length);
        ownershipService.register(upload, 101, "video/mp4", payload.length, "submission");

        assertThatThrownBy(() -> ownershipService.completeOwnedUpload(upload.fileKey(), 202))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(put(upload, "video/mp4", payload).statusCode()).isBetween(200, 299);
        assertThatCode(() -> storage.verifyUploadedObject(
                upload.fileKey(), "video/mp4", payload.length))
                .doesNotThrowAnyException();
        UploadCompletionResponse completed =
                ownershipService.completeOwnedUpload(upload.fileKey(), 101);
        assertThat(completed.status()).isEqualTo(UploadStatus.UPLOADED);

        HttpResponse<byte[]> get = HTTP.send(
                HttpRequest.newBuilder(URI.create(upload.downloadUrl()))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(payload);

        storage.delete(upload.fileKey());
        assertUploadFailure(upload.fileKey(), "video/mp4", payload.length,
                ErrorCode.UPLOAD_NOT_FOUND);
    }

    @Test
    void presignRejectsUnsupportedMimeAndInvalidSizeBeforeObjectStoreAccess() {
        assertThatThrownBy(() -> storage.presignUpload("video/x-msvideo", 1))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_VIDEO_FORMAT);
        assertThatThrownBy(() -> storage.presignUpload("video/mp4", 0))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VIDEO_TOO_LARGE);
        assertThatThrownBy(() -> storage.presignUpload(
                "video/mp4", properties.getMaxSizeBytes() + 1))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VIDEO_TOO_LARGE);
    }

    @Test
    void presignedPutRejectsContentTypeDifferentFromSignedMetadata() throws Exception {
        byte[] payload = bytes("mime-tamper");
        PresignedUpload upload = storage.presignUpload("video/mp4", payload.length);

        HttpResponse<Void> response = put(upload, "video/webm", payload);

        assertThat(response.statusCode()).isEqualTo(403);
        assertUploadFailure(upload.fileKey(), "video/mp4", payload.length,
                ErrorCode.UPLOAD_NOT_FOUND);
    }

    @Test
    void headVerificationRejectsStoredLengthDifferentFromExpectedMetadata() throws Exception {
        byte[] payload = bytes("metadata-mismatch");
        PresignedUpload upload = storage.presignUpload("video/mp4", payload.length);
        assertThat(put(upload, "video/mp4", payload).statusCode()).isBetween(200, 299);

        try {
            assertUploadFailure(upload.fileKey(), "video/mp4", payload.length + 1,
                    ErrorCode.UPLOAD_OBJECT_MISMATCH);
            assertThatCode(() -> storage.verifyUploadedObject(
                    upload.fileKey(), "video/mp4", payload.length))
                    .doesNotThrowAnyException();
        } finally {
            storage.delete(upload.fileKey());
        }
    }

    @Test
    void expiredPresignedPutIsRejectedByMinio() throws Exception {
        byte[] payload = bytes("expired-upload");
        properties.setSigningTtlSeconds(1);
        PresignedUpload upload;
        try {
            upload = storage.presignUpload("video/mp4", payload.length);
        } finally {
            properties.setSigningTtlSeconds(DEFAULT_SIGNING_TTL_SECONDS);
        }

        Thread.sleep(Duration.ofSeconds(2));
        HttpResponse<Void> response = put(upload, "video/mp4", payload);

        assertThat(response.statusCode()).isEqualTo(403);
        assertUploadFailure(upload.fileKey(), "video/mp4", payload.length,
                ErrorCode.UPLOAD_NOT_FOUND);
    }

    @Test
    void expiredPendingOrphanIsDeletedFromMinioAndRegistry() throws Exception {
        byte[] payload = bytes("abandoned-presigned-upload");
        PresignedUpload upload = storage.presignUpload("video/mp4", payload.length);
        assertThat(put(upload, "video/mp4", payload).statusCode()).isBetween(200, 299);

        Instant now = Instant.parse("2026-08-22T03:00:00Z");
        UploadRecord orphan = new UploadRecord(
                upload.fileKey(), 101, "video/mp4", payload.length, "submission",
                now.minus(Duration.ofDays(2)));
        UploadRecordRepository repository = Mockito.mock(UploadRecordRepository.class);
        when(repository.findOrphansForUpdate(now.minus(Duration.ofHours(24)), 50))
                .thenReturn(List.of(orphan));
        UploadOrphanCleanupService cleanup = new UploadOrphanCleanupService(
                repository, storage, Clock.fixed(now, ZoneOffset.UTC),
                Mockito.mock(PreproductionScheduledMutationGuard.class));
        ReflectionTestUtils.setField(cleanup, "schedulingEnabled", true);
        ReflectionTestUtils.setField(cleanup, "uploadsEnabled", true);
        ReflectionTestUtils.setField(cleanup, "cleanupEnabled", true);
        ReflectionTestUtils.setField(cleanup, "maxAge", Duration.ofHours(24));
        ReflectionTestUtils.setField(cleanup, "batchSize", 50);

        cleanup.cleanupOrphans();

        verify(repository).delete(orphan);
        assertUploadFailure(upload.fileKey(), "video/mp4", payload.length,
                ErrorCode.UPLOAD_NOT_FOUND);
    }

    private static HttpResponse<Void> put(PresignedUpload upload, String contentType,
                                          byte[] payload) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", contentType)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build(), HttpResponse.BodyHandlers.discarding());
    }

    private static void assertUploadFailure(String fileKey, String contentType,
                                            long sizeBytes, ErrorCode errorCode) {
        assertThatThrownBy(() -> storage.verifyUploadedObject(fileKey, contentType, sizeBytes))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

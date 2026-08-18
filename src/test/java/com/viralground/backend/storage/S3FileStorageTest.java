package com.viralground.backend.storage;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3FileStorageTest {

    private static final Instant NOW = Instant.parse("2026-08-13T03:00:00Z");

    @Mock S3Client s3Client;
    @Mock S3Presigner presigner;
    @Mock UploadRecordRepository uploadRecordRepository;
    @Mock PresignedPutObjectRequest presignedPut;
    @Mock PresignedGetObjectRequest presignedGet;

    private FileStorageProperties properties;
    private S3FileStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        properties = new FileStorageProperties();
        properties.getS3().setBucket("viralground-test");
        properties.setSigningTtlSeconds(900);
        properties.setAllowedContentTypes(List.of("video/mp4", "video/quicktime", "video/webm"));
        properties.setAllowedImageContentTypes(List.of("image/jpeg", "image/png", "image/webp"));
        storage = new S3FileStorage(properties, s3Client, presigner, uploadRecordRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void presignUpload_signsExactBucketKeyContentTypeAndTtl() {
        when(presignedPut.url()).thenReturn(url("https://storage.test/put?signature=secret"));
        when(presignedGet.url()).thenReturn(url("https://storage.test/get?signature=secret"));
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPut);
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedGet);

        PresignedUpload result = storage.presignUpload("video/mp4", 123_456L);

        ArgumentCaptor<PutObjectPresignRequest> captor =
                ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(captor.capture());
        PutObjectPresignRequest signed = captor.getValue();
        assertThat(signed.signatureDuration()).hasSeconds(900);
        assertThat(signed.putObjectRequest().bucket()).isEqualTo("viralground-test");
        assertThat(signed.putObjectRequest().key()).isEqualTo(result.fileKey());
        assertThat(signed.putObjectRequest().contentType()).isEqualTo("video/mp4");
        assertThat(signed.putObjectRequest().contentLength()).isEqualTo(123_456L);
        assertThat(result.fileKey()).startsWith("submissions/").endsWith(".mp4");
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void verifyUploadedObject_acceptsExactHeadMetadata() {
        String key = "submissions/7ce9b7eb-719a-4d87-853b-cd51fb98e2b0.mp4";
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentType("video/mp4").contentLength(321L).build());

        storage.verifyUploadedObject(key, "video/mp4", 321L);

        ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("viralground-test");
        assertThat(captor.getValue().key()).isEqualTo(key);
    }

    @Test
    void verifyUploadedObject_rejectsContentTypeOrLengthMismatch() {
        String key = "submissions/7ce9b7eb-719a-4d87-853b-cd51fb98e2b0.mp4";
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentType("video/webm").contentLength(1L).build());

        assertThatThrownBy(() -> storage.verifyUploadedObject(key, "video/mp4", 321L))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UPLOAD_OBJECT_MISMATCH);
    }

    @Test
    void verifyUploadedObject_rejectsUnmanagedKeyBeforeHead() {
        assertThatThrownBy(() -> storage.verifyUploadedObject("../outside.mp4", "video/mp4", 1L))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UPLOAD_NOT_FOUND);
        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void exists_returnsFalseForHead404() {
        String key = "thumbnails/7ce9b7eb-719a-4d87-853b-cd51fb98e2b0.png";
        when(uploadRecordRepository.existsByFileKeyAndStatus(key, UploadStatus.UPLOADED))
                .thenReturn(true);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("not found").build());

        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void exists_doesNotExposePendingObject() {
        String key = "thumbnails/7ce9b7eb-719a-4d87-853b-cd51fb98e2b0.png";
        when(uploadRecordRepository.existsByFileKeyAndStatus(key, UploadStatus.UPLOADED))
                .thenReturn(false);

        assertThat(storage.exists(key)).isFalse();

        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void presignImage_rejectsUnsupportedTypeBeforeSigning() {
        assertThatThrownBy(() -> storage.presignImageUpload("image/gif", 10L))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_IMAGE_FORMAT);
        verify(presigner, never()).presignPutObject(any(PutObjectPresignRequest.class));
    }

    private static java.net.URL url(String value) {
        try {
            return URI.create(value).toURL();
        } catch (java.net.MalformedURLException e) {
            throw new AssertionError(e);
        }
    }
}

package com.viralground.backend.storage;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "files.storage", havingValue = "s3")
public class S3FileStorage implements FileStorage {

    private static final Map<String, String> VIDEO_EXTENSIONS = Map.of(
            "video/mp4", "mp4",
            "video/quicktime", "mov",
            "video/webm", "webm"
    );
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    private final FileStorageProperties properties;
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final UploadRecordRepository uploadRecordRepository;
    private final Clock clock;

    public S3FileStorage(FileStorageProperties properties, S3Client s3Client,
                         S3Presigner presigner, UploadRecordRepository uploadRecordRepository,
                         Clock clock) {
        this.properties = properties;
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.uploadRecordRepository = uploadRecordRepository;
        this.clock = clock;
    }

    @Override
    public PresignedUpload presignUpload(String contentType, long sizeBytes) {
        validateVideo(contentType, sizeBytes);
        return buildPresign("submissions/", VIDEO_EXTENSIONS.get(contentType),
                contentType, sizeBytes);
    }

    @Override
    public PresignedUpload presignImageUpload(String contentType, long sizeBytes) {
        validateImage(contentType, sizeBytes);
        return buildPresign("thumbnails/", IMAGE_EXTENSIONS.get(contentType),
                contentType, sizeBytes);
    }

    private PresignedUpload buildPresign(String prefix, String extension, String contentType,
                                         long sizeBytes) {
        String fileKey = prefix + UUID.randomUUID() + "." + extension;
        Duration ttl = Duration.ofSeconds(properties.getSigningTtlSeconds());
        Instant expiresAt = clock.instant().plus(ttl);
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket())
                .key(fileKey)
                .contentType(contentType)
                .contentLength(sizeBytes)
                .build();
        String uploadUrl = presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(put)
                        .build())
                .url().toExternalForm();
        String downloadUrl = presignDownload(fileKey, ttl);
        return new PresignedUpload(fileKey, uploadUrl, downloadUrl, expiresAt);
    }

    @Override
    public String signedDownloadUrl(String fileKey) {
        StorageObjectKeyPolicy.requireManagedKey(fileKey);
        return presignDownload(fileKey, Duration.ofSeconds(properties.getSigningTtlSeconds()));
    }

    private String presignDownload(String fileKey, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket())
                .key(fileKey)
                .build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(get)
                        .build())
                .url().toExternalForm();
    }

    @Override
    public void delete(String fileKey) {
        StorageObjectKeyPolicy.requireManagedKey(fileKey);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket())
                    .key(fileKey)
                    .build());
        } catch (SdkClientException | S3Exception e) {
            throw storageFailure("객체 삭제 실패", e);
        }
    }

    @Override
    public void verifyUploadedObject(String fileKey, String expectedContentType,
                                     long expectedSizeBytes) {
        StorageObjectKeyPolicy.requireKeyMatchesContentType(fileKey, expectedContentType);
        if (expectedSizeBytes <= 0) {
            throw new AppException(ErrorCode.UPLOAD_OBJECT_MISMATCH);
        }
        HeadObjectResponse head = headRequired(fileKey);
        String actualType = StorageObjectKeyPolicy.normalize(head.contentType());
        String expectedType = StorageObjectKeyPolicy.normalize(expectedContentType);
        Long actualLength = head.contentLength();
        if (!expectedType.equals(actualType)
                || actualLength == null || actualLength != expectedSizeBytes) {
            throw new AppException(ErrorCode.UPLOAD_OBJECT_MISMATCH);
        }
    }

    @Override
    public boolean exists(String fileKey) {
        try {
            StorageObjectKeyPolicy.requireManagedKey(fileKey);
            if (!uploadRecordRepository.existsByFileKeyAndStatus(fileKey, UploadStatus.UPLOADED)) {
                return false;
            }
            headRequired(fileKey);
            return true;
        } catch (AppException e) {
            return false;
        }
    }

    private HeadObjectResponse headRequired(String fileKey) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket())
                    .key(fileKey)
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new AppException(ErrorCode.UPLOAD_NOT_FOUND);
            }
            throw storageFailure("객체 메타데이터 조회 실패", e);
        } catch (SdkClientException e) {
            throw storageFailure("객체 저장소 통신 실패", e);
        }
    }

    private String bucket() {
        return properties.getS3().getBucket();
    }

    private void validateVideo(String contentType, long sizeBytes) {
        if (contentType == null || !properties.getAllowedContentTypes().contains(contentType)
                || !VIDEO_EXTENSIONS.containsKey(contentType)) {
            throw new AppException(ErrorCode.INVALID_VIDEO_FORMAT);
        }
        if (sizeBytes <= 0 || sizeBytes > properties.getMaxSizeBytes()) {
            throw new AppException(ErrorCode.VIDEO_TOO_LARGE);
        }
    }

    private void validateImage(String contentType, long sizeBytes) {
        if (contentType == null || !properties.getAllowedImageContentTypes().contains(contentType)
                || !IMAGE_EXTENSIONS.containsKey(contentType)) {
            throw new AppException(ErrorCode.INVALID_IMAGE_FORMAT);
        }
        if (sizeBytes <= 0 || sizeBytes > properties.getMaxImageSizeBytes()) {
            throw new AppException(ErrorCode.IMAGE_TOO_LARGE);
        }
    }

    private static IllegalStateException storageFailure(String message, Exception cause) {
        return new IllegalStateException(message, cause);
    }
}

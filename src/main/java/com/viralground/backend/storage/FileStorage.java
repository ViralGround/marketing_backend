package com.viralground.backend.storage;

/**
 * 파일 스토리지 추상화. 기본 구현은 {@link LocalFileStorage} (로컬 디스크 + HMAC 서명 URL).
 * 운영 전환 시 S3 구현을 동일 인터페이스로 제공.
 */
public interface FileStorage {

    PresignedUpload presignUpload(String contentType, long sizeBytes);

    String signedDownloadUrl(String fileKey);

    void delete(String fileKey);
}

package com.viralground.backend.storage;

/**
 * 파일 스토리지 추상화. 기본 구현은 {@link LocalFileStorage} (로컬 디스크 + HMAC 서명 URL).
 * 운영 전환 시 S3 구현을 동일 인터페이스로 제공.
 */
public interface FileStorage {

    PresignedUpload presignUpload(String contentType, long sizeBytes);

    PresignedUpload presignImageUpload(String contentType, long sizeBytes);

    String signedDownloadUrl(String fileKey);

    void delete(String fileKey);

    /**
     * 해당 키의 파일이 실제로 업로드되어 존재하는지 확인.
     * submitWork 시 클라이언트가 임의의 문자열을 fileKey 로 보내도 빈 파일 참조가 저장되지 않게 막는다.
     */
    boolean exists(String fileKey);
}

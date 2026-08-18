package com.viralground.backend.storage;

import java.time.Instant;

public record PresignedUpload(
        String fileKey,
        String uploadUrl,
        String downloadUrl,
        Instant expiresAt
) {
    /** URL query에 서명 비밀이 있으므로 우발적인 객체 로깅에서 항상 제거한다. */
    @Override
    public String toString() {
        return "PresignedUpload[fileKey=" + fileKey
                + ", uploadUrl=<redacted>, downloadUrl=<redacted>, expiresAt=" + expiresAt + "]";
    }
}

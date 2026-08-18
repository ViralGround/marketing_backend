package com.viralground.backend.storage;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PresignedUploadRedactionTest {

    @Test
    void toStringNeverContainsBearerUrlsOrSignatures() {
        PresignedUpload upload = new PresignedUpload(
                "submissions/test.mp4",
                "https://storage.test/object?X-Amz-Signature=put-secret",
                "https://storage.test/object?X-Amz-Signature=get-secret",
                Instant.parse("2026-08-13T06:00:00Z"));

        assertThat(upload.toString())
                .contains("uploadUrl=<redacted>", "downloadUrl=<redacted>")
                .doesNotContain("put-secret", "get-secret", "X-Amz-Signature");
    }
}

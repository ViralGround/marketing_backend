package com.viralground.backend.dto.file;

import com.viralground.backend.storage.UploadStatus;

import java.time.Instant;

public record UploadCompletionResponse(
        String fileKey,
        UploadStatus status,
        Instant uploadedAt
) {
}

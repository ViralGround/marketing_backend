package com.viralground.backend.storage;

import java.time.Instant;

public record PresignedUpload(
        String fileKey,
        String uploadUrl,
        String downloadUrl,
        Instant expiresAt
) {}

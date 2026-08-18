package com.viralground.backend.dto.file;

import jakarta.validation.constraints.NotBlank;

public record CompleteUploadRequest(@NotBlank String fileKey) {
}

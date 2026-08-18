package com.viralground.backend.storage;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class StorageObjectKeyPolicy {

    private static final Pattern SAFE_KEY = Pattern.compile(
            "^(submissions|thumbnails)/[A-Za-z0-9][A-Za-z0-9._-]{0,159}$");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "video/mp4", "mp4",
            "video/quicktime", "mov",
            "video/webm", "webm",
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    private StorageObjectKeyPolicy() {
    }

    static void requireManagedKey(String fileKey) {
        if (fileKey == null || fileKey.isBlank() || fileKey.length() > 180
                || fileKey.contains("..") || fileKey.contains("//")
                || !SAFE_KEY.matcher(fileKey).matches()) {
            throw new AppException(ErrorCode.UPLOAD_NOT_FOUND);
        }
    }

    static void requireKeyMatchesContentType(String fileKey, String contentType) {
        requireManagedKey(fileKey);
        String extension = EXTENSIONS.get(normalize(contentType));
        if (extension == null || !fileKey.toLowerCase(Locale.ROOT).endsWith("." + extension)) {
            throw new AppException(ErrorCode.UPLOAD_OBJECT_MISMATCH);
        }
        boolean expectedImage = normalize(contentType).startsWith("image/");
        if (expectedImage != fileKey.startsWith("thumbnails/")) {
            throw new AppException(ErrorCode.UPLOAD_OBJECT_MISMATCH);
        }
    }

    static String normalize(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }
}

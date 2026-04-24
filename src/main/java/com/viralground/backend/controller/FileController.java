package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.storage.FileStorage;
import com.viralground.backend.storage.LocalFileStorage;
import com.viralground.backend.storage.PresignedUpload;
import com.viralground.backend.storage.StoredFile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Optional;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorage fileStorage;
    private final Optional<LocalFileStorage> localFileStorage;

    @PostMapping("/presign-upload")
    public ResponseEntity<PresignedUpload> presignUpload(
            @Valid @RequestBody PresignUploadRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null || authUser.getRole() != Role.CREATOR) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return ResponseEntity.ok(fileStorage.presignUpload(request.contentType(), request.sizeBytes()));
    }

    @PutMapping("/upload/{*fileKey}")
    public ResponseEntity<Void> upload(
            @PathVariable String fileKey,
            @RequestParam String sig,
            @RequestParam long exp,
            @RequestHeader("Content-Type") String contentType,
            HttpServletRequest request) throws IOException {
        LocalFileStorage local = requireLocal();
        local.acceptUpload(stripLeadingSlash(fileKey), sig, exp,
                request.getInputStream(), contentType, request.getContentLengthLong());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{*fileKey}")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable String fileKey,
            @RequestParam String sig,
            @RequestParam long exp) {
        LocalFileStorage local = requireLocal();
        StoredFile served = local.serveDownload(stripLeadingSlash(fileKey), sig, exp);
        String contentType = served.contentType() != null ? served.contentType() : "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(served.sizeBytes())
                .body(served.resource());
    }

    private LocalFileStorage requireLocal() {
        return localFileStorage.orElseThrow(() -> new AppException(ErrorCode.SUBMISSION_NOT_FOUND));
    }

    private static String stripLeadingSlash(String v) {
        return v != null && v.startsWith("/") ? v.substring(1) : v;
    }

    public record PresignUploadRequest(
            @NotBlank String contentType,
            @Positive long sizeBytes
    ) {}
}

package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.file.CompleteUploadRequest;
import com.viralground.backend.dto.file.UploadCompletionResponse;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.storage.FileStorage;
import com.viralground.backend.storage.LocalFileStorage;
import com.viralground.backend.storage.PresignedUpload;
import com.viralground.backend.storage.StoredFile;
import com.viralground.backend.storage.UploadOwnershipService;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditService;
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
    private final UploadOwnershipService uploadOwnershipService;
    private final AuditService auditService;

    @PostMapping("/presign-upload")
    public ResponseEntity<PresignedUpload> presignUpload(
            @Valid @RequestBody PresignUploadRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null || authUser.getRole() != Role.CREATOR) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        PresignedUpload upload = fileStorage.presignUpload(request.contentType(), request.sizeBytes());
        uploadOwnershipService.register(upload, authUser.getId(), request.contentType(), request.sizeBytes(), "VIDEO");
        auditService.record(authUser, AuditAction.FILE_PRESIGNED,
                "upload", upload.fileKey(), "SUCCESS", "VIDEO");
        return ResponseEntity.ok(upload);
    }

    @PostMapping("/presign-upload/image")
    public ResponseEntity<PresignedUpload> presignImageUpload(
            @Valid @RequestBody PresignUploadRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null
                || (authUser.getRole() != Role.COMPANY && authUser.getRole() != Role.ADMIN)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        PresignedUpload upload = fileStorage.presignImageUpload(request.contentType(), request.sizeBytes());
        uploadOwnershipService.register(upload, authUser.getId(), request.contentType(), request.sizeBytes(), "IMAGE");
        auditService.record(authUser, AuditAction.FILE_PRESIGNED,
                "upload", upload.fileKey(), "SUCCESS", "IMAGE");
        return ResponseEntity.ok(upload);
    }

    /**
     * S3/R2 직접 PUT 후 호출한다. 인증된 발급 소유자만 완료할 수 있고, 저장소 HEAD
     * 결과가 발급 당시 key/content-type/content-length와 일치해야 UPLOADED가 된다.
     */
    @PostMapping("/complete-upload")
    public ResponseEntity<UploadCompletionResponse> completeUpload(
            @Valid @RequestBody CompleteUploadRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        UploadCompletionResponse completed = uploadOwnershipService.completeOwnedUpload(
                request.fileKey(), authUser.getId());
        auditService.record(authUser, AuditAction.FILE_UPLOADED,
                "upload", completed.fileKey(), "SUCCESS", completed.status().name());
        return ResponseEntity.ok(completed);
    }

    @PutMapping("/upload/{*fileKey}")
    public ResponseEntity<Void> upload(
            @PathVariable String fileKey,
            @RequestParam String sig,
            @RequestParam long exp,
            @RequestHeader("Content-Type") String contentType,
            HttpServletRequest request) throws IOException {
        LocalFileStorage local = requireLocal();
        String key = stripLeadingSlash(fileKey);
        boolean isImage = key != null && key.startsWith("thumbnails/");
        // Content-Length 미선언(-1) 또는 상한 초과는 본문을 받기 전에 즉시 거부.
        // 실제 스트림 초과는 LocalFileStorage.acceptUpload 내부의 BoundedInputStream 이 끊는다.
        long declared = request.getContentLengthLong();
        long maxBytes = isImage ? local.getMaxImageSizeBytes() : local.getMaxSizeBytes();
        if (declared < 0 || declared > maxBytes) {
            throw new AppException(isImage ? ErrorCode.IMAGE_TOO_LARGE : ErrorCode.VIDEO_TOO_LARGE);
        }
        local.acceptUpload(key, sig, exp, request.getInputStream(), contentType, declared);
        uploadOwnershipService.completeSignedLocalUpload(key);
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

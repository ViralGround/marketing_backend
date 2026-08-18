package com.viralground.backend.storage;

import com.viralground.backend.dto.file.UploadCompletionResponse;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class UploadOwnershipService {
    private final UploadRecordRepository repository;
    private final FileStorage fileStorage;
    private final Clock clock;

    @Transactional
    public void register(PresignedUpload upload, Integer ownerId, String contentType,
                         long sizeBytes, String category) {
        repository.save(new UploadRecord(upload.fileKey(), ownerId, contentType, sizeBytes,
                category, clock.instant()));
        log.atInfo()
                .addKeyValue("event", "storage_upload_registered")
                .addKeyValue("category", category)
                .addKeyValue("contentType", contentType)
                .addKeyValue("sizeBytes", sizeBytes)
                .log("Upload registration created");
    }

    @Transactional
    public UploadCompletionResponse completeOwnedUpload(String fileKey, Integer ownerId) {
        UploadRecord record = repository.findByFileKeyForUpdate(fileKey)
                .orElseThrow(() -> new AppException(ErrorCode.UPLOAD_NOT_FOUND));
        if (!record.getOwnerId().equals(ownerId)) {
            log.atWarn()
                    .addKeyValue("event", "storage_upload_owner_rejected")
                    .addKeyValue("category", record.getCategory())
                    .log("Upload completion rejected for non-owner");
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        if (record.getStatus() == UploadStatus.UPLOADED) {
            log.atInfo()
                    .addKeyValue("event", "storage_upload_completion_replayed")
                    .addKeyValue("category", record.getCategory())
                    .log("Upload completion replay accepted");
            return response(record);
        }

        verifyAndMark(record);
        return response(record);
    }

    /**
     * 로컬 HMAC PUT 경로의 기존 동작을 보존한다. 소유자 인증 대신 일회성에 가까운
     * 서명 URL 검증을 통과한 직후에만 호출되며, DB의 발급 정보와 실제 파일도 비교한다.
     */
    @Transactional
    public void completeSignedLocalUpload(String fileKey) {
        UploadRecord record = repository.findByFileKeyForUpdate(fileKey)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_SIGNATURE));
        if (record.getStatus() == UploadStatus.UPLOADED) return;
        verifyAndMark(record);
    }

    private void verifyAndMark(UploadRecord record) {
        try {
            fileStorage.verifyUploadedObject(
                    record.getFileKey(), record.getContentType(), record.getSizeBytes());
        } catch (AppException e) {
            log.atWarn()
                    .addKeyValue("event", "storage_upload_verification_rejected")
                    .addKeyValue("category", record.getCategory())
                    .addKeyValue("reason", e.getErrorCode().getCode())
                    .log("Upload verification rejected");
            if (e.getErrorCode() == ErrorCode.UPLOAD_OBJECT_MISMATCH) {
                deleteRejectedObject(record);
            }
            throw e;
        }
        record.markUploaded(clock.instant());
        log.atInfo()
                .addKeyValue("event", "storage_upload_completed")
                .addKeyValue("category", record.getCategory())
                .addKeyValue("contentType", record.getContentType())
                .addKeyValue("sizeBytes", record.getSizeBytes())
                .log("Upload verified and completed");
    }

    private void deleteRejectedObject(UploadRecord record) {
        try {
            fileStorage.delete(record.getFileKey());
        } catch (RuntimeException deletionFailure) {
            log.atError()
                    .addKeyValue("event", "storage_rejected_object_delete_failed")
                    .addKeyValue("category", record.getCategory())
                    .addKeyValue("failureType", deletionFailure.getClass().getSimpleName())
                    .log("Rejected upload cleanup failed");
        }
    }

    private static UploadCompletionResponse response(UploadRecord record) {
        return new UploadCompletionResponse(
                record.getFileKey(), record.getStatus(), record.getUploadedAt());
    }

    @Transactional(readOnly = true)
    public void requireOwnedUpload(String fileKey, Integer ownerId) {
        if (!repository.existsByFileKeyAndOwnerIdAndStatus(
                fileKey, ownerId, UploadStatus.UPLOADED)) {
            throw new AppException(ErrorCode.SUBMISSION_NOT_FOUND);
        }
    }
}

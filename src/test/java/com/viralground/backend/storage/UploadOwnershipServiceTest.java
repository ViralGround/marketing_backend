package com.viralground.backend.storage;

import com.viralground.backend.dto.file.UploadCompletionResponse;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadOwnershipServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T04:00:00Z");

    @Mock UploadRecordRepository repository;
    @Mock FileStorage fileStorage;

    @Test
    void registerCreatesPendingRecordWithInjectedClock() {
        UploadOwnershipService service = service();
        PresignedUpload upload = new PresignedUpload(
                "submissions/test.mp4", "secret-put-url", "secret-get-url", NOW.plusSeconds(900));

        service.register(upload, 31, "video/mp4", 123L, "VIDEO");

        ArgumentCaptor<UploadRecord> captor = ArgumentCaptor.forClass(UploadRecord.class);
        verify(repository).save(captor.capture());
        UploadRecord record = captor.getValue();
        assertThat(record.getOwnerId()).isEqualTo(31);
        assertThat(record.getStatus()).isEqualTo(UploadStatus.PENDING);
        assertThat(record.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void completeOwnedUploadVerifiesMetadataBeforeTransition() {
        UploadRecord record = pendingRecord(31);
        when(repository.findByFileKeyForUpdate(record.getFileKey())).thenReturn(Optional.of(record));

        UploadCompletionResponse response = service().completeOwnedUpload(record.getFileKey(), 31);

        verify(fileStorage).verifyUploadedObject(record.getFileKey(), "video/mp4", 123L);
        assertThat(response.status()).isEqualTo(UploadStatus.UPLOADED);
        assertThat(response.uploadedAt()).isEqualTo(NOW);
        assertThat(record.getStatus()).isEqualTo(UploadStatus.UPLOADED);
    }

    @Test
    void completeOwnedUploadFailsClosedForNonOwnerWithoutHead() {
        UploadRecord record = pendingRecord(31);
        when(repository.findByFileKeyForUpdate(record.getFileKey())).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service().completeOwnedUpload(record.getFileKey(), 99))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(fileStorage, never()).verifyUploadedObject(
                record.getFileKey(), record.getContentType(), record.getSizeBytes());
        assertThat(record.getStatus()).isEqualTo(UploadStatus.PENDING);
    }

    @Test
    void completedOwnerRetryIsIdempotentAndDoesNotHeadAgain() {
        UploadRecord record = pendingRecord(31);
        Instant firstCompletion = NOW.minusSeconds(30);
        record.markUploaded(firstCompletion);
        when(repository.findByFileKeyForUpdate(record.getFileKey())).thenReturn(Optional.of(record));

        UploadCompletionResponse response = service().completeOwnedUpload(record.getFileKey(), 31);

        assertThat(response.uploadedAt()).isEqualTo(firstCompletion);
        verify(fileStorage, never()).verifyUploadedObject(
                record.getFileKey(), record.getContentType(), record.getSizeBytes());
    }

    @Test
    void missingRecordReturnsUploadNotFound() {
        when(repository.findByFileKeyForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().completeOwnedUpload("missing", 31))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UPLOAD_NOT_FOUND);
    }

    @Test
    void metadataMismatchKeepsPendingAndDeletesRejectedObject() {
        UploadRecord record = pendingRecord(31);
        when(repository.findByFileKeyForUpdate(record.getFileKey())).thenReturn(Optional.of(record));
        doThrow(new AppException(ErrorCode.UPLOAD_OBJECT_MISMATCH))
                .when(fileStorage).verifyUploadedObject(
                        record.getFileKey(), record.getContentType(), record.getSizeBytes());

        assertThatThrownBy(() -> service().completeOwnedUpload(record.getFileKey(), 31))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UPLOAD_OBJECT_MISMATCH);
        assertThat(record.getStatus()).isEqualTo(UploadStatus.PENDING);
        verify(fileStorage).delete(record.getFileKey());
    }

    private UploadOwnershipService service() {
        return new UploadOwnershipService(repository, fileStorage,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UploadRecord pendingRecord(int ownerId) {
        return new UploadRecord(
                "submissions/7ce9b7eb-719a-4d87-853b-cd51fb98e2b0.mp4",
                ownerId, "video/mp4", 123L, "VIDEO", NOW.minusSeconds(10));
    }
}

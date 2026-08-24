package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.file.CompleteUploadRequest;
import com.viralground.backend.dto.file.UploadCompletionResponse;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.storage.FileStorage;
import com.viralground.backend.storage.LocalFileStorage;
import com.viralground.backend.storage.UploadOwnershipService;
import com.viralground.backend.storage.UploadStatus;
import com.viralground.backend.logging.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileControllerUploadCompletionTest {

    @Test
    void completeUploadPassesOnlyAuthenticatedMemberIdToOwnershipBoundary() {
        FileStorage storage = mock(FileStorage.class);
        UploadOwnershipService ownership = mock(UploadOwnershipService.class);
        FileController controller = new FileController(
                storage, Optional.<LocalFileStorage>empty(), ownership, mock(AuditService.class));
        ReflectionTestUtils.setField(controller, "uploadsFeatureEnabled", true);
        String key = "submissions/7ce9b7eb-719a-4d87-853b-cd51fb98e2b0.mp4";
        Instant uploadedAt = Instant.parse("2026-08-13T05:00:00Z");
        when(ownership.completeOwnedUpload(key, 31)).thenReturn(
                new UploadCompletionResponse(key, UploadStatus.UPLOADED, uploadedAt));

        var response = controller.completeUpload(
                new CompleteUploadRequest(key),
                new AuthUser(31, "not-logged@example.invalid", Role.CREATOR, "hidden"));

        assertThat(response.getBody().status()).isEqualTo(UploadStatus.UPLOADED);
        verify(ownership).completeOwnedUpload(key, 31);
    }

    @Test
    void completeUploadRejectsMissingAuthenticationBeforeService() {
        UploadOwnershipService ownership = mock(UploadOwnershipService.class);
        FileController controller = new FileController(
                mock(FileStorage.class), Optional.<LocalFileStorage>empty(), ownership,
                mock(AuditService.class));
        ReflectionTestUtils.setField(controller, "uploadsFeatureEnabled", true);

        assertThatThrownBy(() -> controller.completeUpload(
                new CompleteUploadRequest("submissions/test.mp4"), null))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void completeUploadFailsClosedWhenFeatureIsDisabled() {
        UploadOwnershipService ownership = mock(UploadOwnershipService.class);
        FileController controller = new FileController(
                mock(FileStorage.class), Optional.<LocalFileStorage>empty(), ownership,
                mock(AuditService.class));
        ReflectionTestUtils.setField(controller, "uploadsFeatureEnabled", false);

        assertThatThrownBy(() -> controller.completeUpload(
                new CompleteUploadRequest("submissions/test.mp4"),
                new AuthUser(31, "qa@example.test", Role.CREATOR, "QA")))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UPLOAD_FEATURE_DISABLED);
    }
}

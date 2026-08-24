package com.viralground.backend.storage;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UploadOrphanCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-22T02:00:00Z");

    @Test
    void deletesOnlyRepositorySelectedExpiredPendingObjects() {
        UploadRecordRepository repository = mock(UploadRecordRepository.class);
        FileStorage storage = mock(FileStorage.class);
        UploadRecord orphan = new UploadRecord(
                "members/7/submission/orphan.mp4", 7, "video/mp4", 10, "submission",
                NOW.minus(Duration.ofDays(2)));
        when(repository.findOrphansForUpdate(eq(NOW.minus(Duration.ofHours(24))), eq(50)))
                .thenReturn(List.of(orphan));
        UploadOrphanCleanupService service = service(repository, storage, true);

        service.cleanupOrphans();

        verify(storage).delete(orphan.getFileKey());
        verify(repository).delete(orphan);
    }

    @Test
    void globalOrJobKillSwitchPreventsStorageAndDatabaseAccess() {
        UploadRecordRepository repository = mock(UploadRecordRepository.class);
        FileStorage storage = mock(FileStorage.class);
        UploadOrphanCleanupService service = service(repository, storage, false);

        service.cleanupOrphans();

        verifyNoInteractions(repository, storage);
    }

    @Test
    void retainsRecordWhenObjectDeletionFails() {
        UploadRecordRepository repository = mock(UploadRecordRepository.class);
        FileStorage storage = mock(FileStorage.class);
        UploadRecord orphan = new UploadRecord(
                "members/7/submission/orphan.mp4", 7, "video/mp4", 10, "submission",
                NOW.minus(Duration.ofDays(2)));
        when(repository.findOrphansForUpdate(any(), anyInt())).thenReturn(List.of(orphan));
        doThrow(new IllegalStateException("store unavailable")).when(storage).delete(orphan.getFileKey());
        UploadOrphanCleanupService service = service(repository, storage, true);

        service.cleanupOrphans();

        verify(repository, never()).delete(any());
    }

    private UploadOrphanCleanupService service(UploadRecordRepository repository,
                                                FileStorage storage,
                                                boolean enabled) {
        UploadOrphanCleanupService service = new UploadOrphanCleanupService(
                repository, storage, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PreproductionScheduledMutationGuard.class));
        ReflectionTestUtils.setField(service, "schedulingEnabled", enabled);
        ReflectionTestUtils.setField(service, "uploadsEnabled", enabled);
        ReflectionTestUtils.setField(service, "cleanupEnabled", enabled);
        ReflectionTestUtils.setField(service, "maxAge", Duration.ofHours(24));
        ReflectionTestUtils.setField(service, "batchSize", 50);
        return service;
    }
}

package com.viralground.backend.storage;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/** Removes objects that were uploaded through a presign but never completed by their owner. */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadOrphanCleanupService {

    private final UploadRecordRepository repository;
    private final FileStorage fileStorage;
    private final Clock clock;
    private final PreproductionScheduledMutationGuard scheduledMutationGuard;

    @Value("${app.scheduling.enabled:false}")
    private boolean schedulingEnabled;

    @Value("${features.uploads.enabled:false}")
    private boolean uploadsEnabled;

    @Value("${files.orphan-cleanup.enabled:false}")
    private boolean cleanupEnabled;

    @Value("${files.orphan-cleanup.max-age:24h}")
    private Duration maxAge;

    @Value("${files.orphan-cleanup.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${files.orphan-cleanup.fixed-delay:1h}")
    @Transactional
    public void cleanupOrphans() {
        if (!schedulingEnabled || !uploadsEnabled || !cleanupEnabled) return;
        scheduledMutationGuard.requireSafe();
        Duration safeAge = maxAge == null || maxAge.isNegative() || maxAge.isZero()
                ? Duration.ofHours(24) : maxAge;
        int safeBatchSize = Math.max(1, Math.min(batchSize, 100));
        var cutoff = clock.instant().minus(safeAge);

        for (UploadRecord record : repository.findOrphansForUpdate(cutoff, safeBatchSize)) {
            try {
                // S3 DeleteObject is idempotent, so a crash after delete but before DB commit is safe to retry.
                fileStorage.delete(record.getFileKey());
                repository.delete(record);
                log.atInfo()
                        .addKeyValue("event", "storage_orphan_deleted")
                        .addKeyValue("category", record.getCategory())
                        .log("Expired pending upload removed");
            } catch (RuntimeException failure) {
                IllegalStateException safeSignal = new IllegalStateException(
                        "storage_orphan_cleanup_failed type=" + failure.getClass().getSimpleName());
                Sentry.captureException(safeSignal);
                log.atError()
                        .addKeyValue("event", "storage_orphan_cleanup_failed")
                        .addKeyValue("category", record.getCategory())
                        .addKeyValue("errorType", failure.getClass().getSimpleName())
                        .log("Expired pending upload cleanup failed");
            }
        }
    }
}

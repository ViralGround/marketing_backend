package com.viralground.backend.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
import java.util.List;

public interface UploadRecordRepository extends JpaRepository<UploadRecord, String> {
    boolean existsByFileKeyAndOwnerIdAndStatus(String fileKey, Integer ownerId, UploadStatus status);

    boolean existsByFileKeyAndStatus(String fileKey, UploadStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UploadRecord u where u.fileKey = :fileKey")
    Optional<UploadRecord> findByFileKeyForUpdate(@Param("fileKey") String fileKey);

    @Query(value = """
            SELECT * FROM upload_records
            WHERE status = 'PENDING' AND created_at < :cutoff
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UploadRecord> findOrphansForUpdate(@Param("cutoff") Instant cutoff,
                                             @Param("batchSize") int batchSize);
}

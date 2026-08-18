package com.viralground.backend.storage;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "upload_records", indexes = {
        @Index(name = "idx_upload_records_owner", columnList = "owner_id,created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadRecord {
    @Id
    @Column(name = "file_key", length = 180)
    private String fileKey;

    @Column(name = "owner_id", nullable = false)
    private Integer ownerId;

    @Column(name = "content_type", nullable = false, length = 80)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 24)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UploadStatus status;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UploadRecord(String fileKey, Integer ownerId, String contentType, long sizeBytes,
                        String category, Instant createdAt) {
        this.fileKey = fileKey;
        this.ownerId = ownerId;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.category = category;
        this.status = UploadStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void markUploaded(Instant completedAt) {
        if (status == UploadStatus.UPLOADED) return;
        this.status = UploadStatus.UPLOADED;
        this.uploadedAt = completedAt;
    }
}

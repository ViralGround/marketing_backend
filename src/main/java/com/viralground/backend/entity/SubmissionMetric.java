package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 크리에이터가 수동으로 입력한 영상 성과 스냅샷. application 1건당 1 row 로 upsert.
 * SNS API 연동 전 MVP 구현 — 크리에이터가 SNS 게시물의 조회수/좋아요/댓글을 직접 입력한다.
 */
@Entity
@Table(name = "submission_metrics",
        uniqueConstraints = @UniqueConstraint(columnNames = {"application_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmissionMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "application_id", nullable = false, unique = true)
    private Integer applicationId;

    @Column(nullable = false)
    @Builder.Default
    private Long views = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long likes = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long comments = 0L;

    @Column(name = "external_url")
    private String externalUrl;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

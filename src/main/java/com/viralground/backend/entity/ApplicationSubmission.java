package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 크리에이터가 캠페인에 제출한 결과물 1건의 스냅샷과 검토 결과를 기록한다.
 * 재제출마다 새 row 가 추가되어 관리자/기업이 제출 이력과 수정요청 사유를 추적할 수 있다.
 */
@Entity
@Table(name = "application_submissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "application_id", nullable = false)
    private Integer applicationId;

    @Column(name = "video_file_key")
    private String videoFileKey;

    @Column(name = "video_content_type")
    private String videoContentType;

    @Column(name = "video_size_bytes")
    private Long videoSizeBytes;

    @Column(name = "submission_url")
    private String submissionUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubmissionReviewStatus status = SubmissionReviewStatus.SUBMITTED;

    @Column(name = "reviewer_id")
    private Integer reviewerId;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @PrePersist
    protected void onCreate() {
        submittedAt = LocalDateTime.now();
    }
}

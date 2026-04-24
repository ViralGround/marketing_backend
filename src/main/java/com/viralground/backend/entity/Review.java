package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 캠페인이 정산 완료된 후 기업↔크리에이터 간 양방향으로 1회 남기는 평점/리뷰.
 * unique(applicationId, authorRole) 로 각 당사자가 한 건만 작성 가능하다.
 */
@Entity
@Table(name = "reviews",
        uniqueConstraints = @UniqueConstraint(columnNames = {"application_id", "author_role"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "application_id", nullable = false)
    private Integer applicationId;

    @Column(name = "author_id", nullable = false)
    private Integer authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false)
    private Role authorRole;

    /** 평가 대상자(author 가 CREATOR 면 기업 담당자 id, COMPANY 면 크리에이터 id). */
    @Column(name = "target_id", nullable = false)
    private Integer targetId;

    @Column(nullable = false)
    private Integer rating;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

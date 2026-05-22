package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 랜딩 페이지의 "가벼운 상담신청" 폼 제출 기록.
 * 인증 없이 누구나 제출 가능하므로 controller 단에서 입력 검증 + 향후 레이트리밋 적용.
 */
@Entity
@Table(name = "contact_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContactRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "brand_name", nullable = false, length = 200)
    private String brandName;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

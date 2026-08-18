package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.Instant;

/**
 * 랜딩 페이지의 "가벼운 상담신청" 폼 제출 기록.
 * 인증 없이 누구나 제출 가능하므로 controller 단에서 입력 검증, honeypot, IP rate-limit을 적용한다.
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

    /** 화면에 실제 표시한 개인정보 문서의 immutable version ID. legacy 행은 null일 수 있다. */
    @Column(name = "privacy_consent_version", length = 80, updatable = false)
    private String privacyConsentVersion;

    @Column(name = "privacy_consented_at", updatable = false)
    private Instant privacyConsentedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

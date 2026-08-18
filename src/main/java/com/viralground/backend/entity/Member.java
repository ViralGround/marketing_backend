package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.Instant;

@Entity
@Table(name = "members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MemberStatus status = MemberStatus.APPROVED;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private Boolean emailVerified = false;

    @Column(name = "agreed_terms_at")
    private LocalDateTime agreedTermsAt;

    @Column(name = "agreed_privacy_at")
    private LocalDateTime agreedPrivacyAt;

    @Column(name = "agreed_age14_at")
    private LocalDateTime agreedAge14At;

    @Column(name = "agreed_third_party_at")
    private LocalDateTime agreedThirdPartyAt;

    @Column(name = "marketing_opt_in_at")
    private LocalDateTime marketingOptInAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    public void withdraw(Instant at) {
        this.status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = java.util.Objects.requireNonNull(at, "at");
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // CreatorProfile / CompanyProfile 양방향 매핑은 제거.
    // mappedBy 측 OneToOne 은 fetch=LAZY 가 실제로는 무시되어 Member 조회마다
    // 추가 SELECT 가 발생(Hibernate 알려진 동작). 코드에서 m.getCreatorProfile()
    // 같은 호출이 없고, 프로필은 *Repository.findByMemberId / findByMemberIdIn 으로
    // 명시 조회하므로 양방향이 불필요했음.

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

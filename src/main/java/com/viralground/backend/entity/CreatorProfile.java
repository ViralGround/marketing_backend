package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "creator_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatorProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "member_id", nullable = false, unique = true)
    private Integer memberId;

    @Column(name = "can_edit", nullable = false)
    @Builder.Default
    private Boolean canEdit = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "editing_skill")
    @Builder.Default
    private EditingSkill editingSkill = EditingSkill.LOW;

    @Enumerated(EnumType.STRING)
    @Column(name = "editing_tool")
    private EditingTool editingTool;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private Integer age;

    @Column(name = "face_exposure", nullable = false)
    @Builder.Default
    private Boolean faceExposure = false;

    @Column(name = "profile_image")
    private String profileImage;

    @Column(name = "instagram_id")
    private String instagramId;

    @Column(name = "tiktok_id")
    private String tiktokId;

    @Column(name = "youtube_id")
    private String youtubeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", insertable = false, updatable = false)
    private Member member;

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

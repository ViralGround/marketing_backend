package com.viralground.backend.dto.admin;

import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.entity.Member;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class MemberDetailResponse {

    private final Integer id;
    private final String email;
    private final String name;
    private final String role;
    private final String status;
    private final Boolean emailVerified;
    private final LocalDateTime createdAt;
    private final ProfileInfo profile;
    private final long applicationCount;

    public MemberDetailResponse(Member m, CreatorProfile p, long appCount) {
        this.id = m.getId();
        this.email = m.getEmail();
        this.name = m.getName();
        this.role = m.getRole().name();
        this.status = m.getStatus().name();
        this.emailVerified = m.getEmailVerified();
        this.createdAt = m.getCreatedAt();
        this.applicationCount = appCount;
        this.profile = p != null ? new ProfileInfo(p) : null;
    }

    public record ProfileInfo(
            String gender, Integer age, Boolean faceExposure,
            String editingTool, String instagramId, String tiktokId, String youtubeId) {
        ProfileInfo(CreatorProfile p) {
            this(p.getGender() != null ? p.getGender().name() : null,
                    p.getAge(), p.getFaceExposure(),
                    p.getEditingTool() != null ? p.getEditingTool().name() : null,
                    p.getInstagramId(), p.getTiktokId(), p.getYoutubeId());
        }
    }
}

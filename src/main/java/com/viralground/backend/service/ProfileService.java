package com.viralground.backend.service;

import com.viralground.backend.dto.profile.UpdateProfileRequest;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.repository.CreatorProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final CreatorProfileRepository creatorProfileRepository;

    public Map<String, Object> getProfile(Integer memberId) {
        Optional<CreatorProfile> opt = creatorProfileRepository.findByMemberId(memberId);
        if (opt.isEmpty()) {
            return Map.of("hasProfile", false);
        }
        CreatorProfile p = opt.get();
        Map<String, Object> profile = new java.util.LinkedHashMap<>();
        profile.put("canEdit", p.getCanEdit());
        profile.put("editingSkill", p.getEditingSkill() != null ? p.getEditingSkill().name() : null);
        profile.put("editingTool", p.getEditingTool() != null ? p.getEditingTool().name() : null);
        profile.put("faceExposure", p.getFaceExposure());
        profile.put("profileImage", p.getProfileImage() != null ? p.getProfileImage() : "");
        profile.put("instagramId", p.getInstagramId() != null ? p.getInstagramId() : "");
        profile.put("tiktokId", p.getTiktokId() != null ? p.getTiktokId() : "");
        profile.put("youtubeId", p.getYoutubeId() != null ? p.getYoutubeId() : "");
        profile.put("publicProfileOptIn", Boolean.TRUE.equals(p.getPublicProfileOptIn()));
        return Map.of(
                "hasProfile", true,
                "profile", profile
        );
    }

    @Transactional
    public void updateProfile(Integer memberId, UpdateProfileRequest req) {
        CreatorProfile profile = creatorProfileRepository.findByMemberId(memberId)
                .orElse(CreatorProfile.builder().memberId(memberId).build());

        profile.setCanEdit(req.getCanEdit());
        profile.setEditingSkill(req.getEditingSkill());
        profile.setFaceExposure(req.getFaceExposure());
        profile.setProfileImage(req.getProfileImage());
        profile.setInstagramId(req.getInstagramId());
        boolean wasPublic = Boolean.TRUE.equals(profile.getPublicProfileOptIn());
        boolean makePublic = Boolean.TRUE.equals(req.getPublicProfileOptIn());
        profile.setPublicProfileOptIn(makePublic);
        if (makePublic && !wasPublic) {
            profile.setPublicProfileConsentedAt(java.time.LocalDateTime.now());
        } else if (!makePublic) {
            profile.setPublicProfileConsentedAt(null);
        }

        creatorProfileRepository.save(profile);
    }
}

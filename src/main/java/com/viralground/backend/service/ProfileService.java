package com.viralground.backend.service;

import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.entity.EditingSkill;
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
        return Map.of(
                "hasProfile", true,
                "profile", Map.of(
                        "canEdit", p.getCanEdit(),
                        "editingSkill", p.getEditingSkill() != null ? p.getEditingSkill().name() : null,
                        "editingTool", p.getEditingTool() != null ? p.getEditingTool().name() : null,
                        "faceExposure", p.getFaceExposure(),
                        "profileImage", p.getProfileImage() != null ? p.getProfileImage() : "",
                        "instagramId", p.getInstagramId() != null ? p.getInstagramId() : "",
                        "tiktokId", p.getTiktokId() != null ? p.getTiktokId() : "",
                        "youtubeId", p.getYoutubeId() != null ? p.getYoutubeId() : ""
                )
        );
    }

    @Transactional
    public void updateProfile(Integer memberId, Map<String, Object> body) {
        CreatorProfile profile = creatorProfileRepository.findByMemberId(memberId)
                .orElse(CreatorProfile.builder().memberId(memberId).build());

        if (body.containsKey("canEdit")) {
            profile.setCanEdit(Boolean.parseBoolean(body.get("canEdit").toString()));
        }
        if (body.containsKey("editingSkill") && body.get("editingSkill") != null) {
            profile.setEditingSkill(EditingSkill.valueOf(body.get("editingSkill").toString()));
        }
        if (body.containsKey("faceExposure")) {
            profile.setFaceExposure(Boolean.parseBoolean(body.get("faceExposure").toString()));
        }
        if (body.containsKey("profileImage")) {
            profile.setProfileImage(body.get("profileImage") != null ? body.get("profileImage").toString() : null);
        }
        if (body.containsKey("instagramId")) {
            profile.setInstagramId(body.get("instagramId") != null ? body.get("instagramId").toString() : null);
        }

        creatorProfileRepository.save(profile);
    }
}

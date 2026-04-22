package com.viralground.backend.dto.profile;

import com.viralground.backend.entity.EditingSkill;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateProfileRequest {

    @NotNull(message = "편집 가능 여부를 선택해주세요")
    private Boolean canEdit;

    @NotNull(message = "편집 실력을 선택해주세요")
    private EditingSkill editingSkill;

    @NotNull(message = "얼굴 공개 여부를 선택해주세요")
    private Boolean faceExposure;

    private String profileImage;
    private String instagramId;
}

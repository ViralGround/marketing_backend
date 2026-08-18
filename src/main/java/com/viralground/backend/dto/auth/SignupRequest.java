package com.viralground.backend.dto.auth;

import com.viralground.backend.entity.EditingTool;
import com.viralground.backend.entity.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 12, max = 72, message = "비밀번호는 12~72자로 입력해주세요")
    private String password;

    @NotBlank
    @Size(max = 80, message = "이름은 80자 이하여야 합니다")
    private String name;

    @NotBlank
    private String verifiedToken;

    @NotNull
    private Gender gender;

    @NotNull
    @Min(1)
    private Integer age;

    @NotNull
    private Boolean faceExposure;

    @NotNull
    private EditingTool editingTool;

    private String instagramId;
    private String tiktokId;
    private String youtubeId;

    private boolean agreedTerms;
    private boolean agreedPrivacy;
    private boolean agreedAge14;
    private boolean agreedThirdParty;
    private boolean marketingOptIn;

    @NotBlank
    @Size(max = 80)
    private String termsVersion;

    @NotBlank
    @Size(max = 80)
    private String privacyVersion;

    @NotBlank
    @Size(max = 80)
    private String age14Version;

    @NotBlank
    @Size(max = 80)
    private String creatorThirdPartyVersion;

    /** marketingOptIn=true일 때만 서비스 계층에서 필수/정확 일치 검증한다. */
    @Size(max = 80)
    private String marketingVersion;
}

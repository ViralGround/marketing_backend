package com.viralground.backend.dto.auth;

import com.viralground.backend.entity.EditingTool;
import com.viralground.backend.entity.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    @NotBlank
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
}

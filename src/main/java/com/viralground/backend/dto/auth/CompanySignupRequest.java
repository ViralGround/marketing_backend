package com.viralground.backend.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompanySignupRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 12, max = 72, message = "비밀번호는 12~72자로 입력해주세요")
    private String password;

    @NotBlank
    @Size(max = 80)
    private String name;

    @NotBlank
    private String verifiedToken;

    @NotBlank
    private String companyName;

    @NotBlank
    @Pattern(regexp = "\\d{10}", message = "사업자등록번호는 숫자 10자리여야 합니다")
    private String businessNumber;

    @NotBlank
    private String representativeName;

    @NotBlank
    private String contactName;

    @NotBlank
    @Pattern(regexp = "^[0-9+() -]{8,20}$", message = "연락처 형식을 확인해주세요")
    private String contactPhone;

    private String address;
    @Size(max = 500)
    private String homepage;
    private String industry;

    private boolean agreedTerms;
    private boolean agreedPrivacy;
    private boolean agreedAge14;
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

    /** marketingOptIn=true일 때만 서비스 계층에서 필수/정확 일치 검증한다. */
    @Size(max = 80)
    private String marketingVersion;
}

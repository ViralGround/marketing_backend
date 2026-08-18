package com.viralground.backend.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PasswordResetConfirmRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String code;

    // 가입(SignupRequest)과 동일한 비밀번호 정책을 유지한다.
    @NotBlank
    @Size(min = 12, max = 72, message = "비밀번호는 12~72자로 입력해주세요")
    private String newPassword;
}

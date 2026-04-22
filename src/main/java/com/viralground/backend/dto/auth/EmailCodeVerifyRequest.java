package com.viralground.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailCodeVerifyRequest {

    @NotBlank
    private String email;

    @NotBlank
    private String code;
}

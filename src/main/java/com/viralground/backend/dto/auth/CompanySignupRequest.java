package com.viralground.backend.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompanySignupRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    @NotBlank
    private String name;

    @NotBlank
    private String verifiedToken;

    @NotBlank
    private String companyName;

    @NotBlank
    private String businessNumber;

    @NotBlank
    private String representativeName;

    @NotBlank
    private String contactName;

    @NotBlank
    private String contactPhone;

    private String address;
    private String homepage;
    private String industry;
}

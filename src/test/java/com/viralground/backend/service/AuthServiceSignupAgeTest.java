package com.viralground.backend.service;

import com.viralground.backend.dto.auth.SignupRequest;
import com.viralground.backend.entity.EditingTool;
import com.viralground.backend.entity.Gender;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuthServiceSignupAgeTest {

    @Mock MemberRepository memberRepository;
    @Mock CreatorProfileRepository creatorProfileRepository;
    @Mock CompanyProfileRepository companyProfileRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock EmailVerificationService emailVerificationService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock LegalConsentService legalConsentService;

    @InjectMocks
    AuthService authService;

    @Test
    void should_UNDERAGE_예외_when_age_14_미만() {
        // given — 만 13세 크리에이터 가입 시도
        SignupRequest req = baseRequest();
        req.setAge(13);

        // when & then — 다른 검증(이메일/중복) 도달 전에 즉시 거부
        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNDERAGE);

        verifyNoInteractions(emailVerificationService, memberRepository,
                creatorProfileRepository, passwordEncoder, legalConsentService);
    }

    @Test
    void should_UNDERAGE_예외_when_age_0() {
        // given
        SignupRequest req = baseRequest();
        req.setAge(0);

        // when & then
        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNDERAGE);
    }

    private SignupRequest baseRequest() {
        SignupRequest req = new SignupRequest();
        req.setEmail("kid@example.com");
        req.setPassword("password123");
        req.setName("아이");
        req.setVerifiedToken("token");
        req.setGender(Gender.FEMALE);
        req.setFaceExposure(false);
        req.setEditingTool(EditingTool.NONE);
        return req;
    }
}

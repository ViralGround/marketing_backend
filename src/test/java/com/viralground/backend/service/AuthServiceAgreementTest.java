package com.viralground.backend.service;

import com.viralground.backend.dto.auth.CompanySignupRequest;
import com.viralground.backend.dto.auth.SignupRequest;
import com.viralground.backend.entity.EditingTool;
import com.viralground.backend.entity.Gender;
import com.viralground.backend.entity.Member;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import com.viralground.backend.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceAgreementTest {

    @Mock MemberRepository memberRepository;
    @Mock CreatorProfileRepository creatorProfileRepository;
    @Mock CompanyProfileRepository companyProfileRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock EmailVerificationService emailVerificationService;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks
    AuthService authService;

    @Test
    void should_AGREEMENT_REQUIRED_when_크리에이터_이용약관_미동의() {
        SignupRequest req = creatorRequest();
        req.setAgreedTerms(false);

        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AGREEMENT_REQUIRED);

        verifyNoInteractions(emailVerificationService, memberRepository,
                creatorProfileRepository, passwordEncoder);
    }

    @Test
    void should_AGREEMENT_REQUIRED_when_크리에이터_개인정보_미동의() {
        SignupRequest req = creatorRequest();
        req.setAgreedPrivacy(false);

        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AGREEMENT_REQUIRED);
    }

    @Test
    void should_AGREEMENT_REQUIRED_when_크리에이터_제3자제공_미동의() {
        SignupRequest req = creatorRequest();
        req.setAgreedThirdParty(false);

        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AGREEMENT_REQUIRED);
    }

    @Test
    void should_AGREEMENT_REQUIRED_when_14세_확인_미동의() {
        SignupRequest req = creatorRequest();
        req.setAgreedAge14(false);

        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AGREEMENT_REQUIRED);
    }

    @Test
    void should_marketingOptInAt_저장_when_체크함() {
        SignupRequest req = creatorRequest();
        req.setMarketingOptIn(true);
        stubCreatorSignupHappyPath();

        authService.signup(req);

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(captor.capture());
        Member saved = captor.getValue();
        assertThat(saved.getAgreedTermsAt()).isNotNull();
        assertThat(saved.getAgreedPrivacyAt()).isNotNull();
        assertThat(saved.getAgreedAge14At()).isNotNull();
        assertThat(saved.getAgreedThirdPartyAt()).isNotNull();
        assertThat(saved.getMarketingOptInAt()).isNotNull();
    }

    @Test
    void should_marketingOptInAt_null_when_미체크() {
        SignupRequest req = creatorRequest();
        req.setMarketingOptIn(false);
        stubCreatorSignupHappyPath();

        authService.signup(req);

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMarketingOptInAt()).isNull();
    }

    @Test
    void should_AGREEMENT_REQUIRED_when_기업_이용약관_미동의() {
        CompanySignupRequest req = companyRequest();
        req.setAgreedTerms(false);

        assertThatThrownBy(() -> authService.signupCompany(req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AGREEMENT_REQUIRED);
    }

    private void stubCreatorSignupHappyPath() {
        lenient().when(memberRepository.existsByEmail(any())).thenReturn(false);
        lenient().when(passwordEncoder.encode(any())).thenReturn("hashed");
        lenient().when(memberRepository.saveAndFlush(any(Member.class)))
                .thenAnswer(inv -> {
                    Member m = inv.getArgument(0);
                    m.setId(1);
                    return m;
                });
    }

    private SignupRequest creatorRequest() {
        SignupRequest req = new SignupRequest();
        req.setEmail("u@example.com");
        req.setPassword("password123");
        req.setName("테스트");
        req.setVerifiedToken("token");
        req.setGender(Gender.FEMALE);
        req.setAge(20);
        req.setFaceExposure(false);
        req.setEditingTool(EditingTool.NONE);
        req.setAgreedTerms(true);
        req.setAgreedPrivacy(true);
        req.setAgreedAge14(true);
        req.setAgreedThirdParty(true);
        return req;
    }

    private CompanySignupRequest companyRequest() {
        CompanySignupRequest req = new CompanySignupRequest();
        req.setEmail("biz@example.com");
        req.setPassword("password123");
        req.setName("담당자");
        req.setVerifiedToken("token");
        req.setCompanyName("주식회사 예시");
        req.setBusinessNumber("123-45-67890");
        req.setRepresentativeName("대표자");
        req.setContactName("담당자");
        req.setContactPhone("010-0000-0000");
        req.setAgreedTerms(true);
        req.setAgreedPrivacy(true);
        req.setAgreedAge14(true);
        return req;
    }
}

package com.viralground.backend.service;

import com.viralground.backend.dto.auth.CompanySignupRequest;
import com.viralground.backend.dto.auth.SignupRequest;
import com.viralground.backend.entity.EditingTool;
import com.viralground.backend.entity.Gender;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.CompanyProfile;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
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
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock LegalConsentService legalConsentService;

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
                creatorProfileRepository, passwordEncoder, legalConsentService);
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
    void should_reject_stale_document_before_email_or_member_mutation() {
        SignupRequest req = creatorRequest();
        doThrow(new AppException(ErrorCode.LEGAL_DOCUMENT_VERSION_MISMATCH))
                .when(legalConsentService).validateCreatorSignup(req);

        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.LEGAL_DOCUMENT_VERSION_MISMATCH);

        verifyNoInteractions(emailVerificationService, memberRepository,
                creatorProfileRepository, passwordEncoder);
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
        verify(legalConsentService).recordCreatorSignup(
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.same(req),
                any(Instant.class));
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

    @Test
    void companySignup_rejectsInvalidPublicHomepageBeforeMemberMutation() {
        CompanySignupRequest req = companyRequest();
        req.setHomepage("https://127.0.0.1/internal");

        assertThatThrownBy(() -> authService.signupCompany(req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PUBLIC_URL);

        verifyNoInteractions(emailVerificationService, memberRepository,
                companyProfileRepository, passwordEncoder);
    }

    @Test
    void companySignup_normalizesHomepageBeforeSavingProfile() {
        CompanySignupRequest req = companyRequest();
        req.setHomepage("  https://viralground.kr/brands  ");
        lenient().when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            member.setId(31);
            return member;
        });

        authService.signupCompany(req);

        ArgumentCaptor<CompanyProfile> profileCaptor =
                ArgumentCaptor.forClass(CompanyProfile.class);
        verify(companyProfileRepository).save(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getHomepage())
                .isEqualTo("https://viralground.kr/brands");
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
        req.setTermsVersion("terms-v1");
        req.setPrivacyVersion("privacy-v1");
        req.setAge14Version("age14-v1");
        req.setCreatorThirdPartyVersion("third-party-v1");
        return req;
    }

    private CompanySignupRequest companyRequest() {
        CompanySignupRequest req = new CompanySignupRequest();
        req.setEmail("biz@example.com");
        req.setPassword("password123");
        req.setName("담당자");
        req.setVerifiedToken("token");
        req.setCompanyName("주식회사 예시");
        req.setBusinessNumber("1234567890");
        req.setRepresentativeName("대표자");
        req.setContactName("담당자");
        req.setContactPhone("010-0000-0000");
        req.setAgreedTerms(true);
        req.setAgreedPrivacy(true);
        req.setAgreedAge14(true);
        req.setTermsVersion("terms-v1");
        req.setPrivacyVersion("privacy-v1");
        req.setAge14Version("age14-v1");
        return req;
    }
}

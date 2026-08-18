package com.viralground.backend.service;

import com.viralground.backend.dto.auth.CompanySignupRequest;
import com.viralground.backend.dto.auth.SignupRequest;
import com.viralground.backend.entity.ConsentType;
import com.viralground.backend.entity.MemberConsentEvidence;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.legal.LegalDocumentProperties;
import com.viralground.backend.repository.MemberConsentEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LegalConsentServiceTest {

    private static final String TERMS = "terms-2026-08-13";
    private static final String PRIVACY = "privacy-2026-08-13";
    private static final String AGE_14 = "age14-2026-08-13";
    private static final String THIRD_PARTY = "creator-third-party-2026-08-13";
    private static final String MARKETING = "marketing-2026-08-13";

    @Mock
    MemberConsentEvidenceRepository evidenceRepository;

    LegalConsentService service;

    @BeforeEach
    void setUp() {
        LegalDocumentProperties properties = new LegalDocumentProperties();
        properties.getDocuments().setTermsVersion(TERMS);
        properties.getDocuments().setPrivacyVersion(PRIVACY);
        properties.getDocuments().setAge14Version(AGE_14);
        properties.getDocuments().setCreatorThirdPartyVersion(THIRD_PARTY);
        properties.getDocuments().setMarketingVersion(MARKETING);
        service = new LegalConsentService(evidenceRepository, properties);
    }

    @Test
    void rejectsStaleOrWhitespaceDifferentVersionBeforeWritingEvidence() {
        SignupRequest request = creatorRequest(false);
        request.setTermsVersion(TERMS + " ");

        assertThatThrownBy(() -> service.validateCreatorSignup(request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.LEGAL_DOCUMENT_VERSION_MISMATCH);

        verify(evidenceRepository, never()).saveAllAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void marketingOptInRequiresExactMarketingDocumentVersion() {
        SignupRequest request = creatorRequest(true);
        request.setMarketingVersion(null);

        assertThatThrownBy(() -> service.validateCreatorSignup(request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.LEGAL_DOCUMENT_VERSION_MISMATCH);
    }

    @Test
    void creatorRejectsEveryChangedRequiredDocumentVersion() {
        SignupRequest privacy = creatorRequest(false);
        privacy.setPrivacyVersion("stale");
        SignupRequest age = creatorRequest(false);
        age.setAge14Version("stale");
        SignupRequest thirdParty = creatorRequest(false);
        thirdParty.setCreatorThirdPartyVersion("stale");

        assertVersionMismatch(() -> service.validateCreatorSignup(privacy));
        assertVersionMismatch(() -> service.validateCreatorSignup(age));
        assertVersionMismatch(() -> service.validateCreatorSignup(thirdParty));
    }

    @Test
    void companyRejectsChangedRequiredDocumentVersion() {
        CompanySignupRequest request = companyRequest(false);
        request.setPrivacyVersion("stale");

        assertVersionMismatch(() -> service.validateCompanySignup(request));
    }

    @Test
    void evidenceWritesRequireTheCallingMemberTransaction() throws NoSuchMethodException {
        Transactional creatorBoundary = LegalConsentService.class
                .getMethod("recordCreatorSignup", Integer.class, SignupRequest.class, Instant.class)
                .getAnnotation(Transactional.class);
        Transactional companyBoundary = LegalConsentService.class
                .getMethod("recordCompanySignup", Integer.class, CompanySignupRequest.class, Instant.class)
                .getAnnotation(Transactional.class);

        assertThat(creatorBoundary).isNotNull();
        assertThat(creatorBoundary.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(companyBoundary).isNotNull();
        assertThat(companyBoundary.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void recordsEveryCreatorAgreementAtOneInstantWithoutRequestMetadata() {
        SignupRequest request = creatorRequest(true);
        Instant agreedAt = Instant.parse("2026-08-13T03:00:00Z");

        service.recordCreatorSignup(42, request, agreedAt);

        ArgumentCaptor<Iterable<MemberConsentEvidence>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Iterable.class);
        verify(evidenceRepository).saveAllAndFlush(captor.capture());
        List<MemberConsentEvidence> saved = (List<MemberConsentEvidence>) captor.getValue();

        assertThat(saved)
                .extracting(MemberConsentEvidence::getConsentType,
                        MemberConsentEvidence::getDocumentVersion)
                .containsExactly(
                        tuple(ConsentType.TERMS_OF_SERVICE, TERMS),
                        tuple(ConsentType.PRIVACY_POLICY, PRIVACY),
                        tuple(ConsentType.AGE_14_CONFIRMATION, AGE_14),
                        tuple(ConsentType.CREATOR_THIRD_PARTY_PROVISION, THIRD_PARTY),
                        tuple(ConsentType.MARKETING_COMMUNICATION, MARKETING));
        assertThat(saved).allSatisfy(item -> {
            assertThat(item.getMemberId()).isEqualTo(42);
            assertThat(item.getAgreedAt()).isEqualTo(agreedAt);
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void omitsMarketingEvidenceWhenCompanyDoesNotOptIn() {
        CompanySignupRequest request = companyRequest(false);
        Instant agreedAt = Instant.parse("2026-08-13T03:00:00Z");

        service.recordCompanySignup(7, request, agreedAt);

        ArgumentCaptor<Iterable<MemberConsentEvidence>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Iterable.class);
        verify(evidenceRepository).saveAllAndFlush(captor.capture());
        List<MemberConsentEvidence> saved = (List<MemberConsentEvidence>) captor.getValue();
        assertThat(saved)
                .extracting(MemberConsentEvidence::getConsentType)
                .containsExactly(
                        ConsentType.TERMS_OF_SERVICE,
                        ConsentType.PRIVACY_POLICY,
                        ConsentType.AGE_14_CONFIRMATION)
                .doesNotContain(ConsentType.MARKETING_COMMUNICATION);
    }

    private SignupRequest creatorRequest(boolean marketingOptIn) {
        SignupRequest request = new SignupRequest();
        request.setTermsVersion(TERMS);
        request.setPrivacyVersion(PRIVACY);
        request.setAge14Version(AGE_14);
        request.setCreatorThirdPartyVersion(THIRD_PARTY);
        request.setMarketingOptIn(marketingOptIn);
        request.setMarketingVersion(marketingOptIn ? MARKETING : null);
        return request;
    }

    private CompanySignupRequest companyRequest(boolean marketingOptIn) {
        CompanySignupRequest request = new CompanySignupRequest();
        request.setTermsVersion(TERMS);
        request.setPrivacyVersion(PRIVACY);
        request.setAge14Version(AGE_14);
        request.setMarketingOptIn(marketingOptIn);
        request.setMarketingVersion(marketingOptIn ? MARKETING : null);
        return request;
    }

    private void assertVersionMismatch(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.LEGAL_DOCUMENT_VERSION_MISMATCH);
    }
}

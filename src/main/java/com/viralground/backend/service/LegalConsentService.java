package com.viralground.backend.service;

import com.viralground.backend.dto.auth.CompanySignupRequest;
import com.viralground.backend.dto.auth.SignupRequest;
import com.viralground.backend.entity.ConsentType;
import com.viralground.backend.entity.MemberConsentEvidence;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.legal.LegalDocumentProperties;
import com.viralground.backend.repository.MemberConsentEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LegalConsentService {

    private final MemberConsentEvidenceRepository evidenceRepository;
    private final LegalDocumentProperties properties;

    /** 회원/이메일 인증 상태를 변경하기 전에 stale 가입 화면을 거부한다. */
    public void validateCreatorSignup(SignupRequest request) {
        verifyExact(request.getTermsVersion(), documents().getTermsVersion());
        verifyExact(request.getPrivacyVersion(), documents().getPrivacyVersion());
        verifyExact(request.getAge14Version(), documents().getAge14Version());
        verifyExact(request.getCreatorThirdPartyVersion(), documents().getCreatorThirdPartyVersion());
        if (request.isMarketingOptIn()) {
            verifyExact(request.getMarketingVersion(), documents().getMarketingVersion());
        }
    }

    /** 회원/이메일 인증 상태를 변경하기 전에 stale 가입 화면을 거부한다. */
    public void validateCompanySignup(CompanySignupRequest request) {
        verifyExact(request.getTermsVersion(), documents().getTermsVersion());
        verifyExact(request.getPrivacyVersion(), documents().getPrivacyVersion());
        verifyExact(request.getAge14Version(), documents().getAge14Version());
        if (request.isMarketingOptIn()) {
            verifyExact(request.getMarketingVersion(), documents().getMarketingVersion());
        }
    }

    /** 비회원 상담 폼에도 화면에 표시한 개인정보 문서와 서버 버전의 exact match를 강제한다. */
    public void validatePrivacyDocumentVersion(String privacyVersion) {
        verifyExact(privacyVersion, documents().getPrivacyVersion());
    }

    /** 바깥의 회원 생성 transaction 없이는 증적만 따로 생성할 수 없다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreatorSignup(Integer memberId, SignupRequest request, Instant agreedAt) {
        validateCreatorSignup(request);
        List<MemberConsentEvidence> evidence = requiredEvidence(memberId, agreedAt,
                request.getTermsVersion(), request.getPrivacyVersion(), request.getAge14Version());
        evidence.add(new MemberConsentEvidence(memberId,
                ConsentType.CREATOR_THIRD_PARTY_PROVISION,
                request.getCreatorThirdPartyVersion(), agreedAt));
        addMarketingEvidenceWhenOptedIn(evidence, memberId,
                request.isMarketingOptIn(), request.getMarketingVersion(), agreedAt);
        evidenceRepository.saveAllAndFlush(evidence);
    }

    /** 바깥의 회원 생성 transaction 없이는 증적만 따로 생성할 수 없다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompanySignup(Integer memberId, CompanySignupRequest request, Instant agreedAt) {
        validateCompanySignup(request);
        List<MemberConsentEvidence> evidence = requiredEvidence(memberId, agreedAt,
                request.getTermsVersion(), request.getPrivacyVersion(), request.getAge14Version());
        addMarketingEvidenceWhenOptedIn(evidence, memberId,
                request.isMarketingOptIn(), request.getMarketingVersion(), agreedAt);
        evidenceRepository.saveAllAndFlush(evidence);
    }

    private List<MemberConsentEvidence> requiredEvidence(
            Integer memberId,
            Instant agreedAt,
            String termsVersion,
            String privacyVersion,
            String age14Version
    ) {
        List<MemberConsentEvidence> evidence = new ArrayList<>();
        evidence.add(new MemberConsentEvidence(memberId,
                ConsentType.TERMS_OF_SERVICE, termsVersion, agreedAt));
        evidence.add(new MemberConsentEvidence(memberId,
                ConsentType.PRIVACY_POLICY, privacyVersion, agreedAt));
        evidence.add(new MemberConsentEvidence(memberId,
                ConsentType.AGE_14_CONFIRMATION, age14Version, agreedAt));
        return evidence;
    }

    private void addMarketingEvidenceWhenOptedIn(
            List<MemberConsentEvidence> evidence,
            Integer memberId,
            boolean marketingOptIn,
            String marketingVersion,
            Instant agreedAt
    ) {
        if (marketingOptIn) {
            evidence.add(new MemberConsentEvidence(memberId,
                    ConsentType.MARKETING_COMMUNICATION, marketingVersion, agreedAt));
        }
    }

    private LegalDocumentProperties.Documents documents() {
        return Objects.requireNonNull(properties.getDocuments(), "legal.documents");
    }

    private void verifyExact(String presentedVersion, String configuredVersion) {
        if (configuredVersion == null || configuredVersion.isBlank()) {
            throw new IllegalStateException("Legal document version is not configured");
        }
        if (!configuredVersion.equals(configuredVersion.trim()) || configuredVersion.length() > 80) {
            throw new IllegalStateException("Legal document version configuration is not canonical");
        }
        if (!configuredVersion.equals(presentedVersion)) {
            throw new AppException(ErrorCode.LEGAL_DOCUMENT_VERSION_MISMATCH);
        }
    }
}

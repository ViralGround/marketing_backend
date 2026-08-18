package com.viralground.backend.service;

import com.viralground.backend.dto.auth.CompanySignupRequest;
import com.viralground.backend.dto.auth.LoginRequest;
import com.viralground.backend.dto.auth.SignupRequest;
import com.viralground.backend.dto.auth.TokenResponse;
import com.viralground.backend.entity.*;
import com.viralground.backend.event.CreatorSignedUpEvent;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditEvent;
import com.viralground.backend.validation.PublicUrlPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import io.jsonwebtoken.Claims;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailVerificationService emailVerificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LegalConsentService legalConsentService;

    private static final int MIN_CREATOR_AGE = 14;

    @Transactional
    public void signup(SignupRequest req) {
        if (req.getAge() == null || req.getAge() < MIN_CREATOR_AGE) {
            throw new AppException(ErrorCode.UNDERAGE);
        }
        if (!req.isAgreedTerms() || !req.isAgreedPrivacy()
                || !req.isAgreedAge14() || !req.isAgreedThirdParty()) {
            throw new AppException(ErrorCode.AGREEMENT_REQUIRED);
        }
        legalConsentService.validateCreatorSignup(req);

        String email = normalize(req.getEmail());

        emailVerificationService.requireVerified(email, req.getVerifiedToken());

        if (memberRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        Instant consentAgreedAt = Instant.now();
        LocalDateTime now = LocalDateTime.ofInstant(consentAgreedAt, ZoneId.systemDefault());
        Member member;
        try {
            member = memberRepository.saveAndFlush(Member.builder()
                    .email(email)
                    .password(passwordEncoder.encode(req.getPassword()))
                    .name(req.getName())
                    .role(Role.CREATOR)
                    .status(MemberStatus.PENDING)
                    .emailVerified(true)
                    .agreedTermsAt(now)
                    .agreedPrivacyAt(now)
                    .agreedAge14At(now)
                    .agreedThirdPartyAt(now)
                    .marketingOptInAt(req.isMarketingOptIn() ? now : null)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        // DB trigger로 보호되는 버전별 증적을 회원 생성과 같은 transaction에 기록한다.
        // legacy timestamp는 기존 조회/호환을 위해 위 Member 컬럼에도 계속 유지한다.
        legalConsentService.recordCreatorSignup(member.getId(), req, consentAgreedAt);

        creatorProfileRepository.save(CreatorProfile.builder()
                .memberId(member.getId())
                .gender(req.getGender())
                .age(req.getAge())
                .faceExposure(req.getFaceExposure())
                .editingTool(req.getEditingTool())
                .instagramId(req.getInstagramId())
                .tiktokId(req.getTiktokId())
                .youtubeId(req.getYoutubeId())
                .build());

        emailVerificationService.consume(email);
        eventPublisher.publishEvent(new CreatorSignedUpEvent(member.getName(), member.getEmail()));
        publishAudit(member, AuditAction.MEMBER_SIGNUP);
    }

    @Transactional
    public void signupCompany(CompanySignupRequest req) {
        if (!req.isAgreedTerms() || !req.isAgreedPrivacy() || !req.isAgreedAge14()) {
            throw new AppException(ErrorCode.AGREEMENT_REQUIRED);
        }
        legalConsentService.validateCompanySignup(req);

        // 회원/동의 레코드를 만들기 전에 공개 URL을 검증해 transaction mutation 자체를 피한다.
        String homepage = PublicUrlPolicy.normalizeOptional(req.getHomepage());

        String email = normalize(req.getEmail());

        emailVerificationService.requireVerified(email, req.getVerifiedToken());

        if (memberRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        Instant consentAgreedAt = Instant.now();
        LocalDateTime now = LocalDateTime.ofInstant(consentAgreedAt, ZoneId.systemDefault());
        Member member;
        try {
            member = memberRepository.saveAndFlush(Member.builder()
                    .email(email)
                    .password(passwordEncoder.encode(req.getPassword()))
                    .name(req.getName())
                    .role(Role.COMPANY)
                    .status(MemberStatus.PENDING)
                    .emailVerified(true)
                    .agreedTermsAt(now)
                    .agreedPrivacyAt(now)
                    .agreedAge14At(now)
                    .marketingOptInAt(req.isMarketingOptIn() ? now : null)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        legalConsentService.recordCompanySignup(member.getId(), req, consentAgreedAt);

        companyProfileRepository.save(CompanyProfile.builder()
                .memberId(member.getId())
                .companyName(req.getCompanyName())
                .businessNumber(req.getBusinessNumber())
                .representativeName(req.getRepresentativeName())
                .contactName(req.getContactName())
                .contactPhone(req.getContactPhone())
                .address(req.getAddress())
                .homepage(homepage)
                .industry(req.getIndustry())
                .build());

        emailVerificationService.consume(email);
        publishAudit(member, AuditAction.MEMBER_SIGNUP);
    }

    public TokenResponse login(LoginRequest req) {
        Member member = memberRepository.findByEmail(normalize(req.getEmail()))
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(req.getPassword(), member.getPassword())) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!member.getEmailVerified()) {
            throw new AppException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        if (member.getStatus() == MemberStatus.PENDING) {
            throw new AppException(ErrorCode.PENDING_APPROVAL);
        }
        if (member.getStatus() == MemberStatus.REJECTED) {
            throw new AppException(ErrorCode.REJECTED);
        }
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            // 계정 존재 여부를 노출하지 않도록 다른 credential 실패와 같은 응답을 사용한다.
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        TokenResponse session = issueSession(member, UUID.randomUUID().toString());
        publishAudit(member, AuditAction.MEMBER_LOGIN);
        return session;
    }

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        Claims claims = jwtService.parseToken(rawRefreshToken);
        if (!jwtService.isTokenType(claims, "refresh") || claims.getId() == null) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }

        RefreshToken stored = refreshTokenRepository.findById(claims.getId())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_TOKEN));
        if (!stored.isUsable(Instant.now())) {
            revokeFamily(stored.getFamilyId());
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }

        Member member = memberRepository.findById(stored.getMemberId())
                .filter(m -> m.getStatus() == MemberStatus.APPROVED && Boolean.TRUE.equals(m.getEmailVerified()))
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_TOKEN));

        JwtService.IssuedRefreshToken issued = jwtService.generateRefreshToken(member, stored.getFamilyId());
        stored.rotateTo(issued.tokenId());
        refreshTokenRepository.save(stored);
        refreshTokenRepository.save(new RefreshToken(
                issued.tokenId(), member.getId(), issued.familyId(), issued.expiresAt()));
        return new TokenResponse(jwtService.generateAccessToken(member), issued.token());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        Claims claims = jwtService.parseToken(rawRefreshToken);
        if (claims == null || claims.getId() == null) return;
        refreshTokenRepository.findById(claims.getId()).ifPresent(stored -> {
            stored.revoke();
            eventPublisher.publishEvent(new AuditEvent(stored.getMemberId(), null,
                    AuditAction.MEMBER_LOGOUT, "member", stored.getMemberId(), "SUCCESS", null));
        });
    }

    private TokenResponse issueSession(Member member, String familyId) {
        JwtService.IssuedRefreshToken issued = jwtService.generateRefreshToken(member, familyId);
        refreshTokenRepository.save(new RefreshToken(
                issued.tokenId(), member.getId(), issued.familyId(), issued.expiresAt()));
        return new TokenResponse(jwtService.generateAccessToken(member), issued.token());
    }

    private void revokeFamily(String familyId) {
        refreshTokenRepository.findAllByFamilyId(familyId).forEach(RefreshToken::revoke);
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private void publishAudit(Member member, AuditAction action) {
        eventPublisher.publishEvent(new AuditEvent(member.getId(), member.getRole().name(), action,
                "member", member.getId(), "SUCCESS", null));
    }
}

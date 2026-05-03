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
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

        String email = normalize(req.getEmail());

        emailVerificationService.requireVerified(email, req.getVerifiedToken());

        if (memberRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        LocalDateTime now = LocalDateTime.now();
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
    }

    @Transactional
    public void signupCompany(CompanySignupRequest req) {
        if (!req.isAgreedTerms() || !req.isAgreedPrivacy() || !req.isAgreedAge14()) {
            throw new AppException(ErrorCode.AGREEMENT_REQUIRED);
        }

        String email = normalize(req.getEmail());

        emailVerificationService.requireVerified(email, req.getVerifiedToken());

        if (memberRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        LocalDateTime now = LocalDateTime.now();
        Member member;
        try {
            member = memberRepository.saveAndFlush(Member.builder()
                    .email(email)
                    .password(passwordEncoder.encode(req.getPassword()))
                    .name(req.getName())
                    .role(Role.COMPANY)
                    .status(MemberStatus.APPROVED)
                    .emailVerified(true)
                    .agreedTermsAt(now)
                    .agreedPrivacyAt(now)
                    .agreedAge14At(now)
                    .marketingOptInAt(req.isMarketingOptIn() ? now : null)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        companyProfileRepository.save(CompanyProfile.builder()
                .memberId(member.getId())
                .companyName(req.getCompanyName())
                .businessNumber(req.getBusinessNumber())
                .representativeName(req.getRepresentativeName())
                .contactName(req.getContactName())
                .contactPhone(req.getContactPhone())
                .address(req.getAddress())
                .homepage(req.getHomepage())
                .industry(req.getIndustry())
                .build());

        emailVerificationService.consume(email);
    }

    public TokenResponse login(LoginRequest req) {
        Member member = memberRepository.findByEmail(normalize(req.getEmail()))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(req.getPassword(), member.getPassword())) {
            throw new AppException(ErrorCode.INVALID_PASSWORD);
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

        return new TokenResponse(
                jwtService.generateAccessToken(member),
                jwtService.generateRefreshToken(member)
        );
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}

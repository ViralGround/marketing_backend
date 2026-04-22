package com.viralground.backend.service;

import com.viralground.backend.dto.auth.CompanySignupRequest;
import com.viralground.backend.dto.auth.LoginRequest;
import com.viralground.backend.dto.auth.SignupRequest;
import com.viralground.backend.dto.auth.TokenResponse;
import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final EmailVerificationService emailVerificationService;

    @Transactional
    public void signup(SignupRequest req) {
        String email = normalize(req.getEmail());

        emailVerificationService.requireVerified(email, req.getVerifiedToken());

        if (memberRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        Member member = memberRepository.save(Member.builder()
                .email(email)
                .password(passwordEncoder.encode(req.getPassword()))
                .name(req.getName())
                .role(Role.CREATOR)
                .status(MemberStatus.PENDING)
                .emailVerified(true)
                .build());

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
        emailService.notifyAdminsOfNewCreator(member.getName(), member.getEmail());
    }

    @Transactional
    public void signupCompany(CompanySignupRequest req) {
        String email = normalize(req.getEmail());

        emailVerificationService.requireVerified(email, req.getVerifiedToken());

        if (memberRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        Member member = memberRepository.save(Member.builder()
                .email(email)
                .password(passwordEncoder.encode(req.getPassword()))
                .name(req.getName())
                .role(Role.COMPANY)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build());

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

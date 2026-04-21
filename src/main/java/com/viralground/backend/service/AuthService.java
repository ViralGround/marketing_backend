package com.viralground.backend.service;

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

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;

    @Transactional
    public void signup(SignupRequest req) {
        if (memberRepository.existsByEmail(req.getEmail())) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        Member member = memberRepository.save(Member.builder()
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .name(req.getName())
                .role(Role.CREATOR)
                .status(MemberStatus.PENDING)
                .emailVerified(false)
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

        String token = generateToken();
        emailVerificationRepository.save(EmailVerification.builder()
                .memberId(member.getId())
                .token(token)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build());

        emailService.sendEmailVerification(member.getEmail(), member.getName(), token);
        emailService.notifyAdminsOfNewCreator(member.getName(), member.getEmail());
    }

    public TokenResponse login(LoginRequest req) {
        Member member = memberRepository.findByEmail(req.getEmail())
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

    @Transactional
    public void verifyEmail(String token) {
        if (token == null || token.isBlank()) {
            throw new AppException(ErrorCode.MISSING_TOKEN);
        }

        EmailVerification ev = emailVerificationRepository.findByToken(token)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_TOKEN));

        if (ev.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AppException(ErrorCode.EXPIRED_TOKEN);
        }

        Member member = memberRepository.findById(ev.getMemberId())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        member.setEmailVerified(true);
        memberRepository.save(member);
        emailVerificationRepository.delete(ev);
    }

    @Transactional
    public void resendVerification(String email) {
        memberRepository.findByEmail(email).ifPresent(member -> {
            if (member.getEmailVerified()) return;

            emailVerificationRepository.findByMemberId(member.getId())
                    .ifPresent(emailVerificationRepository::delete);

            String token = generateToken();
            emailVerificationRepository.save(EmailVerification.builder()
                    .memberId(member.getId())
                    .token(token)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build());

            emailService.sendEmailVerification(member.getEmail(), member.getName(), token);
        });
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}

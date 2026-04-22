package com.viralground.backend.service;

import com.viralground.backend.entity.EmailVerificationCode;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.EmailVerificationCodeRepository;
import com.viralground.backend.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final Pattern EMAIL_REGEX = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final long CODE_EXPIRY_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 5;

    private final EmailVerificationCodeRepository codeRepository;
    private final MemberRepository memberRepository;
    private final EmailService emailService;
    private final JwtService jwtService;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public LocalDateTime requestCode(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new AppException(ErrorCode.INVALID_EMAIL_FORMAT);
        }
        String email = rawEmail.trim().toLowerCase();
        if (!EMAIL_REGEX.matcher(email).matches()) {
            throw new AppException(ErrorCode.INVALID_EMAIL_FORMAT);
        }
        if (memberRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.DUPLICATE_EMAIL);
        }

        String code = generateCode();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(CODE_EXPIRY_MINUTES);

        EmailVerificationCode record = codeRepository.findByEmail(email).orElseGet(() ->
                EmailVerificationCode.builder()
                        .email(email)
                        .code(code)
                        .expiresAt(expiresAt)
                        .attempts(0)
                        .build()
        );
        record.setCode(code);
        record.setExpiresAt(expiresAt);
        record.setAttempts(0);
        record.setVerifiedAt(null);
        codeRepository.save(record);

        emailService.sendVerificationCode(email, code);

        return expiresAt;
    }

    @Transactional
    public String verifyCode(String rawEmail, String rawCode) {
        if (rawEmail == null || rawEmail.isBlank() || rawCode == null || rawCode.isBlank()) {
            throw new AppException(ErrorCode.VERIFICATION_CODE_MISMATCH);
        }
        String email = rawEmail.trim().toLowerCase();
        String code = rawCode.trim();

        EmailVerificationCode record = codeRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.VERIFICATION_CODE_NOT_REQUESTED));

        if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AppException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        if (record.getAttempts() >= MAX_ATTEMPTS) {
            throw new AppException(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        if (!record.getCode().equals(code)) {
            record.setAttempts(record.getAttempts() + 1);
            codeRepository.save(record);
            throw new AppException(ErrorCode.VERIFICATION_CODE_MISMATCH);
        }

        record.setVerifiedAt(LocalDateTime.now());
        codeRepository.save(record);

        return jwtService.generateEmailVerifiedToken(email);
    }

    public void requireVerified(String email, String verifiedToken) {
        if (verifiedToken == null || verifiedToken.isBlank()) {
            throw new AppException(ErrorCode.EMAIL_NOT_VERIFIED_FOR_SIGNUP);
        }
        String normalized = email == null ? "" : email.trim().toLowerCase();
        if (!jwtService.verifyEmailVerifiedToken(verifiedToken, normalized)) {
            throw new AppException(ErrorCode.INVALID_VERIFIED_TOKEN);
        }
        EmailVerificationCode record = codeRepository.findByEmail(normalized)
                .orElseThrow(() -> new AppException(ErrorCode.EMAIL_NOT_VERIFIED_FOR_SIGNUP));
        if (record.getVerifiedAt() == null) {
            throw new AppException(ErrorCode.EMAIL_NOT_VERIFIED_FOR_SIGNUP);
        }
    }

    @Transactional
    public void consume(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        codeRepository.deleteByEmail(normalized);
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}

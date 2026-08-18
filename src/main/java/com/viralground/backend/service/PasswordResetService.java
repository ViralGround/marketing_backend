package com.viralground.backend.service;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.PasswordResetCode;
import com.viralground.backend.entity.RefreshToken;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditEvent;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.PasswordResetCodeRepository;
import com.viralground.backend.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 이메일 코드 기반 비밀번호 재설정.
 *
 * 계정 존재 여부를 응답으로 누출하지 않는 것이 최우선 원칙이다:
 * - requestCode 는 가입 여부와 무관하게 항상 같은 응답(발송 안내)으로 끝난다.
 * - reset 의 실패 코드는 전부 "코드" 기준(미요청/만료/불일치)이라 미가입 이메일과
 *   가입-미요청 이메일이 같은 오류를 받는다.
 * 성공 시 해당 회원의 모든 refresh token 을 즉시 폐기해 탈취된 세션을 끊는다.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Pattern EMAIL_REGEX = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final long CODE_EXPIRY_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 5;

    private final PasswordResetCodeRepository codeRepository;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom random = new SecureRandom();

    /**
     * 코드 발송. 반환되는 만료 시각은 상수 오프셋이라 가입 여부를 드러내지 않는다.
     * 미가입·탈퇴 이메일이면 아무것도 저장/발송하지 않고 조용히 같은 값으로 끝난다.
     */
    @Transactional
    public LocalDateTime requestCode(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new AppException(ErrorCode.INVALID_EMAIL_FORMAT);
        }
        String email = rawEmail.trim().toLowerCase();
        if (!EMAIL_REGEX.matcher(email).matches()) {
            throw new AppException(ErrorCode.INVALID_EMAIL_FORMAT);
        }

        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(CODE_EXPIRY_MINUTES);

        boolean eligible = memberRepository.findByEmail(email)
                .map(member -> member.getStatus() != MemberStatus.WITHDRAWN)
                .orElse(false);
        if (!eligible) {
            return expiresAt;
        }

        String code = generateCode();
        PasswordResetCode record = codeRepository.findByEmail(email).orElseGet(() ->
                PasswordResetCode.builder()
                        .email(email)
                        .code(passwordEncoder.encode(code))
                        .expiresAt(expiresAt)
                        .attempts(0)
                        .build()
        );
        record.setCode(passwordEncoder.encode(code));
        record.setExpiresAt(expiresAt);
        record.setAttempts(0);
        codeRepository.save(record);

        emailService.sendPasswordResetCode(email, code);

        return expiresAt;
    }

    @Transactional
    public void reset(String rawEmail, String rawCode, String newPassword) {
        if (rawEmail == null || rawEmail.isBlank() || rawCode == null || rawCode.isBlank()) {
            throw new AppException(ErrorCode.VERIFICATION_CODE_MISMATCH);
        }
        String email = rawEmail.trim().toLowerCase();
        String code = rawCode.trim();

        PasswordResetCode record = codeRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.VERIFICATION_CODE_NOT_REQUESTED));

        if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AppException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        if (record.getAttempts() >= MAX_ATTEMPTS) {
            throw new AppException(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        if (!passwordEncoder.matches(code, record.getCode())) {
            record.setAttempts(record.getAttempts() + 1);
            codeRepository.save(record);
            throw new AppException(ErrorCode.VERIFICATION_CODE_MISMATCH);
        }

        // 코드 검증을 통과한 뒤에도 회원이 사라졌으면(탈퇴 등) 같은 "미요청" 오류로 끝낸다.
        Member member = memberRepository.findByEmail(email)
                .filter(m -> m.getStatus() != MemberStatus.WITHDRAWN)
                .orElseThrow(() -> new AppException(ErrorCode.VERIFICATION_CODE_NOT_REQUESTED));

        member.setPassword(passwordEncoder.encode(newPassword));
        memberRepository.save(member);

        // 재설정 코드는 일회용 — 성공 즉시 폐기.
        codeRepository.deleteByEmail(email);

        // 기존 세션 전체 종료: 비밀번호를 바꾼 이유가 탈취 의심일 수 있다.
        refreshTokenRepository.findAllByMemberIdAndRevokedAtIsNull(member.getId())
                .forEach(RefreshToken::revoke);

        eventPublisher.publishEvent(new AuditEvent(member.getId(), member.getRole().name(),
                AuditAction.MEMBER_PASSWORD_RESET, "member", member.getId(), "SUCCESS", null));
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}

package com.viralground.backend.service;

import com.viralground.backend.config.PostgresTransactionAdvisoryLock;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.PasswordResetCode;
import com.viralground.backend.entity.RefreshToken;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.logging.AuditEvent;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.PasswordResetCodeRepository;
import com.viralground.backend.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {
    @Mock PasswordResetCodeRepository codeRepository;
    @Mock MemberRepository memberRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock EmailService emailService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PostgresTransactionAdvisoryLock transactionLock;
    private BCryptPasswordEncoder encoder;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        service = new PasswordResetService(
                codeRepository, memberRepository, refreshTokenRepository,
                emailService, encoder, eventPublisher, transactionLock);
    }

    private Member approvedMember() {
        Member member = new Member();
        member.setId(7);
        member.setEmail("person@example.com");
        member.setPassword(encoder.encode("old-password-123"));
        member.setRole(Role.CREATOR);
        member.setStatus(MemberStatus.APPROVED);
        return member;
    }

    @Test
    void requestCodeForUnknownEmailSendsNothingButStillReturnsExpiry() {
        when(memberRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        LocalDateTime expiresAt = service.requestCode("Ghost@example.com");

        assertThat(expiresAt).isAfter(LocalDateTime.now());
        verify(codeRepository, never()).save(any());
        verify(emailService, never()).queuePasswordResetCode(any(), any(), any());
    }

    @Test
    void requestCodeForUnknownEmailStillPerformsOneWayHashWork() {
        PasswordEncoder timingEncoder = mock(PasswordEncoder.class);
        when(timingEncoder.encode(any(CharSequence.class))).thenReturn("dummy-hash");
        PasswordResetService timingSafeService = new PasswordResetService(
                codeRepository, memberRepository, refreshTokenRepository,
                emailService, timingEncoder, eventPublisher, transactionLock);
        when(memberRepository.findByEmail("ghost@example.com"))
                .thenReturn(Optional.empty());

        timingSafeService.requestCode("ghost@example.com");

        verify(timingEncoder).encode(any(CharSequence.class));
        verify(transactionLock).lock(
                PostgresTransactionAdvisoryLock.Scope.PASSWORD_RESET_REQUEST,
                "ghost@example.com");
        verify(emailService, never()).queuePasswordResetCode(any(), any(), any());
    }

    @Test
    void requestCodeStoresOnlyHashWhileSendingPlainCode() {
        when(memberRepository.findByEmail("person@example.com"))
                .thenReturn(Optional.of(approvedMember()));
        when(emailService.canDeliverAuthenticationCode("person@example.com"))
                .thenReturn(true);
        when(codeRepository.findByEmailForUpdate("person@example.com"))
                .thenReturn(Optional.empty());
        when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestCode("Person@example.com");

        ArgumentCaptor<PasswordResetCode> record = ArgumentCaptor.forClass(PasswordResetCode.class);
        ArgumentCaptor<String> sentCode = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idempotencyKey = ArgumentCaptor.forClass(String.class);
        verify(codeRepository).save(record.capture());
        verify(emailService).queuePasswordResetCode(
                eq("person@example.com"), sentCode.capture(), idempotencyKey.capture());
        assertThat(record.getValue().getCode()).isNotEqualTo(sentCode.getValue());
        assertThat(encoder.matches(sentCode.getValue(), record.getValue().getCode())).isTrue();
        assertThat(idempotencyKey.getValue()).startsWith("vg-outbox-")
                .doesNotContain("person@example.com", sentCode.getValue());
    }

    @Test
    void resetChangesPasswordRevokesSessionsAndConsumesCode() {
        Member member = approvedMember();
        PasswordResetCode record = PasswordResetCode.builder()
                .email("person@example.com")
                .code(encoder.encode("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(0)
                .build();
        RefreshToken session = new RefreshToken("tid", 7, "fam", Instant.now().plusSeconds(3600));
        when(codeRepository.findByEmailForUpdate("person@example.com"))
                .thenReturn(Optional.of(record));
        when(memberRepository.findByEmail("person@example.com")).thenReturn(Optional.of(member));
        when(refreshTokenRepository.findAllByMemberIdAndRevokedAtIsNull(7)).thenReturn(List.of(session));

        service.reset("Person@example.com", "123456", "brand-new-password");

        assertThat(encoder.matches("brand-new-password", member.getPassword())).isTrue();
        assertThat(member.getAuthVersion()).isEqualTo(1L);
        assertThat(session.getRevokedAt()).isNotNull();
        verify(codeRepository).deleteByEmail("person@example.com");
        verify(eventPublisher).publishEvent(any(AuditEvent.class));
    }

    @Test
    void resetWithWrongCodeIncrementsAttemptsAndLeavesPasswordUntouched() {
        Member member = approvedMember();
        String originalHash = member.getPassword();
        PasswordResetCode record = PasswordResetCode.builder()
                .email("person@example.com")
                .code(encoder.encode("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(0)
                .build();
        when(codeRepository.findByEmailForUpdate("person@example.com"))
                .thenReturn(Optional.of(record));
        when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.reset("person@example.com", "000000", "brand-new-password"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_RESET_INVALID);

        assertThat(record.getAttempts()).isEqualTo(1);
        assertThat(member.getPassword()).isEqualTo(originalHash);
        verify(refreshTokenRepository, never()).findAllByMemberIdAndRevokedAtIsNull(any());
    }

    @Test
    void unknownAndExistingWrongCodeExposeTheSameGenericCodeAndStatus() {
        PasswordResetCode existing = PasswordResetCode.builder()
                .email("person@example.com")
                .code(encoder.encode("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(0)
                .build();
        when(codeRepository.findByEmailForUpdate("ghost@example.com"))
                .thenReturn(Optional.empty());
        when(codeRepository.findByEmailForUpdate("person@example.com"))
                .thenReturn(Optional.of(existing));
        when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppException unknown = catchResetFailure("ghost@example.com", "000000");
        AppException existingFailure = catchResetFailure("person@example.com", "000000");

        assertThat(unknown.getErrorCode()).isEqualTo(ErrorCode.PASSWORD_RESET_INVALID);
        assertThat(existingFailure.getErrorCode()).isEqualTo(unknown.getErrorCode());
        assertThat(existingFailure.getErrorCode().getStatus())
                .isEqualTo(unknown.getErrorCode().getStatus());
        assertThat(existing.getAttempts()).isEqualTo(1);
    }

    private AppException catchResetFailure(String email, String code) {
        try {
            service.reset(email, code, "brand-new-password");
            throw new AssertionError("password reset should have been rejected");
        } catch (AppException expected) {
            return expected;
        }
    }
}

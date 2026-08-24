package com.viralground.backend.service;

import com.viralground.backend.config.PostgresTransactionAdvisoryLock;
import com.viralground.backend.entity.EmailVerificationCode;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.EmailVerificationCodeRepository;
import com.viralground.backend.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {
    @Mock EmailVerificationCodeRepository codeRepository;
    @Mock MemberRepository memberRepository;
    @Mock EmailService emailService;
    @Mock JwtService jwtService;
    @Mock PostgresTransactionAdvisoryLock transactionLock;
    private BCryptPasswordEncoder encoder;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        service = new EmailVerificationService(
                codeRepository, memberRepository, emailService, jwtService, encoder,
                transactionLock);
    }

    @Test
    void storesOnlyHashWhileSendingPlainCode() {
        when(codeRepository.findByEmailForUpdate("person@example.com"))
                .thenReturn(Optional.empty());
        when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestCode("Person@example.com");

        ArgumentCaptor<EmailVerificationCode> record = ArgumentCaptor.forClass(EmailVerificationCode.class);
        ArgumentCaptor<String> sentCode = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idempotencyKey = ArgumentCaptor.forClass(String.class);
        verify(codeRepository).save(record.capture());
        verify(emailService).queueVerificationCode(
                eq("person@example.com"), sentCode.capture(), idempotencyKey.capture());
        assertThat(record.getValue().getCode()).isNotEqualTo(sentCode.getValue());
        assertThat(encoder.matches(sentCode.getValue(), record.getValue().getCode())).isTrue();
        assertThat(idempotencyKey.getValue()).startsWith("vg-outbox-")
                .doesNotContain("person@example.com", sentCode.getValue());
        InOrder order = inOrder(transactionLock, memberRepository, codeRepository);
        order.verify(transactionLock).lock(
                PostgresTransactionAdvisoryLock.Scope.EMAIL_VERIFICATION_REQUEST,
                "person@example.com");
        order.verify(memberRepository).existsByEmail("person@example.com");
        order.verify(codeRepository).findByEmailForUpdate("person@example.com");
    }

    @Test
    void verificationLocksCodeRowAndCannotBeRepeated() {
        EmailVerificationCode record = EmailVerificationCode.builder()
                .email("person@example.com")
                .code(encoder.encode("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(0)
                .build();
        when(codeRepository.findByEmailForUpdate("person@example.com"))
                .thenReturn(Optional.of(record));
        when(jwtService.generateEmailVerifiedToken("person@example.com"))
                .thenReturn("verified-token");

        assertThat(service.verifyCode("Person@example.com", "123456"))
                .isEqualTo("verified-token");
        assertThat(record.getVerifiedAt()).isNotNull();
        verify(codeRepository).findByEmailForUpdate("person@example.com");

        assertThatThrownBy(() -> service.verifyCode("person@example.com", "123456"))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFIED_TOKEN);
    }

    @Test
    void mismatchUsesLockedRowAndIncrementsAttemptCounter() {
        EmailVerificationCode record = EmailVerificationCode.builder()
                .email("person@example.com")
                .code(encoder.encode("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(0)
                .build();
        when(codeRepository.findByEmailForUpdate("person@example.com"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.verifyCode("person@example.com", "000000"))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_CODE_MISMATCH);

        assertThat(record.getAttempts()).isEqualTo(1);
        verify(codeRepository).save(record);
    }
}

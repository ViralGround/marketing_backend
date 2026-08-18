package com.viralground.backend.service;

import com.viralground.backend.entity.EmailVerificationCode;
import com.viralground.backend.repository.EmailVerificationCodeRepository;
import com.viralground.backend.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {
    @Mock EmailVerificationCodeRepository codeRepository;
    @Mock MemberRepository memberRepository;
    @Mock EmailService emailService;
    @Mock JwtService jwtService;
    private BCryptPasswordEncoder encoder;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        service = new EmailVerificationService(codeRepository, memberRepository, emailService, jwtService, encoder);
    }

    @Test
    void storesOnlyHashWhileSendingPlainCode() {
        when(codeRepository.findByEmail("person@example.com")).thenReturn(Optional.empty());
        when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestCode("Person@example.com");

        ArgumentCaptor<EmailVerificationCode> record = ArgumentCaptor.forClass(EmailVerificationCode.class);
        ArgumentCaptor<String> sentCode = ArgumentCaptor.forClass(String.class);
        verify(codeRepository).save(record.capture());
        verify(emailService).sendVerificationCode(eq("person@example.com"), sentCode.capture());
        assertThat(record.getValue().getCode()).isNotEqualTo(sentCode.getValue());
        assertThat(encoder.matches(sentCode.getValue(), record.getValue().getCode())).isTrue();
    }
}

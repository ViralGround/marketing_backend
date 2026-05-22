package com.viralground.backend.service;

import com.viralground.backend.entity.ContactRequest;
import com.viralground.backend.repository.ContactRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    @Mock ContactRequestRepository repository;
    @Mock EmailService emailService;

    @InjectMocks
    ContactService contactService;

    @Test
    void should_저장하고_관리자에게_알림_이메일_발송() {
        // given
        when(repository.save(any(ContactRequest.class)))
                .thenAnswer(inv -> {
                    ContactRequest c = inv.getArgument(0);
                    c.setId(1);
                    return c;
                });

        // when
        ContactRequest saved = contactService.submit("brand@example.com", "Acme", "홍길동");

        // then — 저장된 값 검증
        ArgumentCaptor<ContactRequest> captor = ArgumentCaptor.forClass(ContactRequest.class);
        verify(repository).save(captor.capture());
        ContactRequest stored = captor.getValue();
        assertThat(stored.getEmail()).isEqualTo("brand@example.com");
        assertThat(stored.getBrandName()).isEqualTo("Acme");
        assertThat(stored.getContactName()).isEqualTo("홍길동");
        assertThat(saved.getId()).isEqualTo(1);

        // and — 관리자 알림 발송 (입력값 그대로 전달)
        verify(emailService).notifyAdminsOfNewContact("brand@example.com", "Acme", "홍길동");
    }

    @Test
    void should_담당자명_공백이면_null_저장() {
        // given — 담당자명은 선택 필드. 빈 문자열은 null 로 정규화한다 (DB 일관성).
        when(repository.save(any(ContactRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // when
        contactService.submit("a@b.com", "Acme", "  ");

        // then
        ArgumentCaptor<ContactRequest> captor = ArgumentCaptor.forClass(ContactRequest.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getContactName()).isNull();
    }

    @Test
    void should_입력값_앞뒤_공백_제거() {
        // given
        when(repository.save(any(ContactRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // when
        contactService.submit("  brand@example.com  ", "  Acme  ", "  홍길동  ");

        // then
        ArgumentCaptor<ContactRequest> captor = ArgumentCaptor.forClass(ContactRequest.class);
        verify(repository).save(captor.capture());
        ContactRequest stored = captor.getValue();
        assertThat(stored.getEmail()).isEqualTo("brand@example.com");
        assertThat(stored.getBrandName()).isEqualTo("Acme");
        assertThat(stored.getContactName()).isEqualTo("홍길동");
    }
}

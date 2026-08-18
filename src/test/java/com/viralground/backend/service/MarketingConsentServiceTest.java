package com.viralground.backend.service;

import com.viralground.backend.dto.account.MarketingConsentRequest;
import com.viralground.backend.entity.MarketingConsentAction;
import com.viralground.backend.entity.MarketingConsentEvent;
import com.viralground.backend.entity.Member;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.legal.LegalDocumentProperties;
import com.viralground.backend.repository.MarketingConsentEventRepository;
import com.viralground.backend.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketingConsentServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock MarketingConsentEventRepository eventRepository;

    private MarketingConsentService service;
    private final Instant now = Instant.parse("2026-08-13T05:00:00Z");

    @BeforeEach
    void setUp() {
        LegalDocumentProperties legal = new LegalDocumentProperties();
        legal.getDocuments().setMarketingVersion("marketing-2026-08-13");
        service = new MarketingConsentService(
                memberRepository,
                eventRepository,
                legal,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void optInRequiresExactCurrentVersionAndPersistsAppendOnlyEvent() {
        Member member = Member.builder().id(7).build();
        when(memberRepository.findById(7)).thenReturn(Optional.of(member));

        var result = service.change(7,
                new MarketingConsentRequest(true, "marketing-2026-08-13"));

        assertThat(result.optedIn()).isTrue();
        assertThat(member.getMarketingOptInAt()).isNotNull();
        ArgumentCaptor<MarketingConsentEvent> event =
                ArgumentCaptor.forClass(MarketingConsentEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo(MarketingConsentAction.OPT_IN);
        assertThat(event.getValue().getDocumentVersion()).isEqualTo("marketing-2026-08-13");
        assertThat(event.getValue().getOccurredAt()).isEqualTo(now);
    }

    @Test
    void optInRejectsStaleVersionWithoutChangingMember() {
        Member member = Member.builder().id(7).build();
        when(memberRepository.findById(7)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.change(7,
                new MarketingConsentRequest(true, "marketing-old")))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.LEGAL_DOCUMENT_VERSION_MISMATCH);

        assertThat(member.getMarketingOptInAt()).isNull();
        verify(memberRepository, never()).save(any());
        verifyNoInteractions(eventRepository);
    }

    @Test
    void optOutDoesNotRequireVersionAndRecordsWithdrawal() {
        Member member = Member.builder()
                .id(9)
                .marketingOptInAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
        when(memberRepository.findById(9)).thenReturn(Optional.of(member));

        var result = service.change(9, new MarketingConsentRequest(false, null));

        assertThat(result.optedIn()).isFalse();
        assertThat(member.getMarketingOptInAt()).isNull();
        ArgumentCaptor<MarketingConsentEvent> event =
                ArgumentCaptor.forClass(MarketingConsentEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo(MarketingConsentAction.OPT_OUT);
        assertThat(event.getValue().getDocumentVersion()).isEqualTo("marketing-2026-08-13");
    }

    @Test
    void repeatedChoiceIsIdempotentAndDoesNotCreateDuplicateEvidence() {
        Member member = Member.builder().id(11).build();
        when(memberRepository.findById(11)).thenReturn(Optional.of(member));

        var result = service.change(11, new MarketingConsentRequest(false, null));

        assertThat(result.optedIn()).isFalse();
        verify(memberRepository, never()).save(any());
        verifyNoInteractions(eventRepository);
    }
}

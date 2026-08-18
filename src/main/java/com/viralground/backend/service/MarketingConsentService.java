package com.viralground.backend.service;

import com.viralground.backend.dto.account.MarketingConsentRequest;
import com.viralground.backend.dto.account.MarketingConsentResponse;
import com.viralground.backend.entity.MarketingConsentAction;
import com.viralground.backend.entity.MarketingConsentEvent;
import com.viralground.backend.entity.Member;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.legal.LegalDocumentProperties;
import com.viralground.backend.repository.MarketingConsentEventRepository;
import com.viralground.backend.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class MarketingConsentService {

    private final MemberRepository memberRepository;
    private final MarketingConsentEventRepository eventRepository;
    private final LegalDocumentProperties legalDocuments;
    private final Clock clock;

    @Transactional(readOnly = true)
    public MarketingConsentResponse get(Integer memberId) {
        return response(memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND)));
    }

    @Transactional
    public MarketingConsentResponse change(Integer memberId, MarketingConsentRequest request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        boolean requested = Boolean.TRUE.equals(request.optedIn());
        boolean current = member.getMarketingOptInAt() != null;
        if (requested == current) return response(member);

        String currentVersion = legalDocuments.getDocuments().getMarketingVersion();
        if (requested && (request.marketingVersion() == null
                || !currentVersion.equals(request.marketingVersion().trim()))) {
            throw new AppException(ErrorCode.LEGAL_DOCUMENT_VERSION_MISMATCH);
        }

        Instant occurredAt = clock.instant();
        if (requested) {
            member.setMarketingOptInAt(LocalDateTime.ofInstant(occurredAt, ZoneId.systemDefault()));
        } else {
            member.setMarketingOptInAt(null);
        }
        memberRepository.save(member);
        eventRepository.saveAndFlush(new MarketingConsentEvent(
                memberId,
                requested ? MarketingConsentAction.OPT_IN : MarketingConsentAction.OPT_OUT,
                currentVersion,
                occurredAt));
        return response(member);
    }

    private static MarketingConsentResponse response(Member member) {
        return new MarketingConsentResponse(
                member.getMarketingOptInAt() != null,
                member.getMarketingOptInAt());
    }
}

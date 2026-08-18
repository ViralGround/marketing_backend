package com.viralground.backend.dto.admin;

import com.viralground.backend.entity.ContactRequest;

import java.time.LocalDateTime;
import java.time.Instant;

public record ContactRequestResponse(
        Integer id,
        String email,
        String brandName,
        String contactName,
        String privacyConsentVersion,
        Instant privacyConsentedAt,
        LocalDateTime createdAt
) {
    public static ContactRequestResponse from(ContactRequest c) {
        return new ContactRequestResponse(
                c.getId(),
                c.getEmail(),
                c.getBrandName(),
                c.getContactName(),
                c.getPrivacyConsentVersion(),
                c.getPrivacyConsentedAt(),
                c.getCreatedAt()
        );
    }
}

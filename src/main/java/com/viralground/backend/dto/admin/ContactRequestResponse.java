package com.viralground.backend.dto.admin;

import com.viralground.backend.entity.ContactRequest;

import java.time.LocalDateTime;

public record ContactRequestResponse(
        Integer id,
        String email,
        String brandName,
        String contactName,
        LocalDateTime createdAt
) {
    public static ContactRequestResponse from(ContactRequest c) {
        return new ContactRequestResponse(
                c.getId(),
                c.getEmail(),
                c.getBrandName(),
                c.getContactName(),
                c.getCreatedAt()
        );
    }
}

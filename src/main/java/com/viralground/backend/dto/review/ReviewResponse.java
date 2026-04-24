package com.viralground.backend.dto.review;

import com.viralground.backend.entity.Review;
import com.viralground.backend.entity.Role;

import java.time.LocalDateTime;

public record ReviewResponse(
        Integer id,
        Integer applicationId,
        Integer authorId,
        Role authorRole,
        String authorName,
        Integer targetId,
        Integer rating,
        String comment,
        LocalDateTime createdAt
) {
    public static ReviewResponse from(Review r, String authorName) {
        return new ReviewResponse(
                r.getId(), r.getApplicationId(), r.getAuthorId(), r.getAuthorRole(),
                authorName, r.getTargetId(), r.getRating(), r.getComment(), r.getCreatedAt());
    }
}

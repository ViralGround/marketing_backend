package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.review.ReviewResponse;
import com.viralground.backend.dto.review.WriteReviewRequest;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.service.ReviewService;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final AuditService auditService;

    @PostMapping("/applications/{id}/reviews")
    public ResponseEntity<Map<String, Object>> write(
            @PathVariable Integer id,
            @Valid @RequestBody WriteReviewRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null
                || (authUser.getRole() != Role.CREATOR && authUser.getRole() != Role.COMPANY)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        var saved = reviewService.writeReview(id, authUser.getId(), authUser.getRole(), req);
        auditService.record(authUser, AuditAction.REVIEW_CREATED,
                "review", saved.getId(), "SUCCESS", null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", saved.getId(), "message", "리뷰가 등록되었습니다."));
    }

    @GetMapping("/applications/{id}/reviews")
    public ResponseEntity<Map<String, Object>> listByApplication(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null) throw new AppException(ErrorCode.FORBIDDEN);
        List<ReviewResponse> items = reviewService.getReviewsOfApplication(
                id, authUser.getId(), authUser.getRole());
        return ResponseEntity.ok(Map.of("reviews", items));
    }

    @GetMapping("/creators/{id}/reviews")
    public ResponseEntity<Map<String, Object>> listReceivedByCreator(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        List<ReviewResponse> items = reviewService.getPublicReviewsReceivedBy(id);
        return ResponseEntity.ok(Map.of("reviews", items));
    }

    @GetMapping("/companies/{id}/reviews")
    public ResponseEntity<Map<String, Object>> listReceivedByCompany(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null) throw new AppException(ErrorCode.FORBIDDEN);
        List<ReviewResponse> items = reviewService.getPublicCompanyReviewsReceivedBy(id);
        return ResponseEntity.ok(Map.of("reviews", items));
    }
}

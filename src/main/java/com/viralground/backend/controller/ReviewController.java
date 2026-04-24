package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.review.ReviewResponse;
import com.viralground.backend.dto.review.WriteReviewRequest;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.service.ReviewService;
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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", saved.getId(), "message", "리뷰가 등록되었습니다."));
    }

    @GetMapping("/applications/{id}/reviews")
    public ResponseEntity<Map<String, Object>> listByApplication(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        // 당사자 여부는 서비스/상위에서 포트폴리오 공개 범위 정책에 따라 열어둠 —
        // 로그인만 요구하는 것으로 MVP 는 단순화 (관리자/기업/크리에이터 모두 열람 가능).
        if (authUser == null) throw new AppException(ErrorCode.FORBIDDEN);
        List<ReviewResponse> items = reviewService.getReviewsOfApplication(id);
        return ResponseEntity.ok(Map.of("reviews", items));
    }

    @GetMapping("/creators/{id}/reviews")
    public ResponseEntity<Map<String, Object>> listReceivedByCreator(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null) throw new AppException(ErrorCode.FORBIDDEN);
        List<ReviewResponse> items = reviewService.getReviewsReceivedBy(id);
        return ResponseEntity.ok(Map.of("reviews", items));
    }

    @GetMapping("/companies/{id}/reviews")
    public ResponseEntity<Map<String, Object>> listReceivedByCompany(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null) throw new AppException(ErrorCode.FORBIDDEN);
        List<ReviewResponse> items = reviewService.getReviewsReceivedBy(id);
        return ResponseEntity.ok(Map.of("reviews", items));
    }
}

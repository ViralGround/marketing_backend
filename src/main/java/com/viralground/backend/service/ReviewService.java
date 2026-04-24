package com.viralground.backend.service;

import com.viralground.backend.dto.review.ReviewResponse;
import com.viralground.backend.dto.review.WriteReviewRequest;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.Review;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final CampaignRepository campaignRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public Review writeReview(Integer applicationId, Integer authorId, Role authorRole,
                              WriteReviewRequest request) {
        if (request.getRating() == null || request.getRating() < 1 || request.getRating() > 5) {
            throw new AppException(ErrorCode.INVALID_RATING);
        }

        CampaignApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));
        if (app.getStatus() != ApplicationStatus.SETTLED) {
            throw new AppException(ErrorCode.REVIEW_NOT_ALLOWED_YET);
        }
        Campaign campaign = campaignRepository.findById(app.getCampaignId())
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));

        // 당사자 검증 + 평가 대상자 결정
        Integer targetId;
        if (authorRole == Role.CREATOR) {
            if (!app.getCreatorId().equals(authorId)) {
                throw new AppException(ErrorCode.FORBIDDEN);
            }
            targetId = campaign.getCreatedById();
        } else if (authorRole == Role.COMPANY) {
            if (!campaign.getCreatedById().equals(authorId)) {
                throw new AppException(ErrorCode.FORBIDDEN);
            }
            targetId = app.getCreatorId();
        } else {
            throw new AppException(ErrorCode.FORBIDDEN);
        }

        if (reviewRepository.existsByApplicationIdAndAuthorRole(applicationId, authorRole)) {
            throw new AppException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }

        return reviewRepository.save(Review.builder()
                .applicationId(applicationId)
                .authorId(authorId)
                .authorRole(authorRole)
                .targetId(targetId)
                .rating(request.getRating())
                .comment(request.getComment())
                .build());
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> getReviewsOfApplication(Integer applicationId) {
        List<Review> reviews = reviewRepository.findByApplicationIdOrderByCreatedAtAsc(applicationId);
        return toResponses(reviews);
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> getReviewsReceivedBy(Integer targetId) {
        List<Review> reviews = reviewRepository.findByTargetIdOrderByCreatedAtDesc(targetId);
        return toResponses(reviews);
    }

    private List<ReviewResponse> toResponses(List<Review> reviews) {
        if (reviews.isEmpty()) return List.of();
        Map<Integer, String> nameById = memberRepository
                .findAllById(reviews.stream().map(Review::getAuthorId).distinct().toList()).stream()
                .collect(Collectors.toMap(Member::getId, Member::getName));
        return reviews.stream()
                .map(r -> ReviewResponse.from(r, nameById.getOrDefault(r.getAuthorId(), "(알 수 없음)")))
                .toList();
    }
}

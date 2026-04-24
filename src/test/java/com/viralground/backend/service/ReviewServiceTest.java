package com.viralground.backend.service;

import com.viralground.backend.dto.review.WriteReviewRequest;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.Review;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock ReviewRepository reviewRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock MemberRepository memberRepository;

    @InjectMocks
    ReviewService reviewService;

    CampaignApplication settledApp;
    Campaign campaign;

    @BeforeEach
    void setUp() {
        settledApp = CampaignApplication.builder()
                .id(10).campaignId(1).creatorId(7)
                .status(ApplicationStatus.SETTLED)
                .build();
        campaign = Campaign.builder()
                .id(1).createdById(50).rewardAmount(30_000).build();
    }

    @Test
    void should_리뷰_저장_when_크리에이터가_작성() {
        // given
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(reviewRepository.existsByApplicationIdAndAuthorRole(10, Role.CREATOR)).thenReturn(false);
        WriteReviewRequest req = new WriteReviewRequest();
        req.setRating(5);
        req.setComment("좋은 경험이었어요");

        // when
        reviewService.writeReview(10, 7, Role.CREATOR, req);

        // then
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).saveAndFlush(captor.capture());
        Review saved = captor.getValue();
        assertThat(saved.getApplicationId()).isEqualTo(10);
        assertThat(saved.getAuthorId()).isEqualTo(7);
        assertThat(saved.getAuthorRole()).isEqualTo(Role.CREATOR);
        assertThat(saved.getTargetId()).isEqualTo(50); // 기업 담당자
        assertThat(saved.getRating()).isEqualTo(5);
    }

    @Test
    void should_기업_리뷰_타겟은_크리에이터_when_기업이_작성() {
        // given
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(reviewRepository.existsByApplicationIdAndAuthorRole(10, Role.COMPANY)).thenReturn(false);
        WriteReviewRequest req = new WriteReviewRequest();
        req.setRating(4);

        // when
        reviewService.writeReview(10, 50, Role.COMPANY, req);

        // then
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTargetId()).isEqualTo(7);
        assertThat(captor.getValue().getAuthorRole()).isEqualTo(Role.COMPANY);
    }

    @Test
    void should_REVIEW_NOT_ALLOWED_YET_예외_when_SETTLED_아님() {
        // given
        settledApp.setStatus(ApplicationStatus.SUBMITTED);
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        WriteReviewRequest req = new WriteReviewRequest();
        req.setRating(5);

        // when & then
        assertThatThrownBy(() -> reviewService.writeReview(10, 7, Role.CREATOR, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_NOT_ALLOWED_YET);
    }

    @Test
    void should_REVIEW_ALREADY_EXISTS_예외_when_중복_작성() {
        // given
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(reviewRepository.existsByApplicationIdAndAuthorRole(10, Role.CREATOR)).thenReturn(true);
        WriteReviewRequest req = new WriteReviewRequest();
        req.setRating(5);

        // when & then
        assertThatThrownBy(() -> reviewService.writeReview(10, 7, Role.CREATOR, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS);
        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void should_FORBIDDEN_예외_when_당사자_아닌_사람이_작성() {
        // given — 다른 크리에이터가 자신이 지원하지 않은 application 에 리뷰 작성 시도
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        WriteReviewRequest req = new WriteReviewRequest();
        req.setRating(5);

        // when & then
        assertThatThrownBy(() -> reviewService.writeReview(10, 999, Role.CREATOR, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void should_INVALID_RATING_예외_when_평점_범위_벗어남() {
        // given
        WriteReviewRequest req = new WriteReviewRequest();
        req.setRating(6);

        // when & then
        assertThatThrownBy(() -> reviewService.writeReview(10, 7, Role.CREATOR, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_RATING);
    }

    @Test
    void should_REVIEW_ALREADY_EXISTS_예외_when_저장_시점_유니크제약_위반() {
        // given — existsBy 는 false 를 반환했지만 경쟁 요청이 먼저 save 해 유니크 제약을 위반하는 TOCTOU 시나리오
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(reviewRepository.existsByApplicationIdAndAuthorRole(10, Role.CREATOR)).thenReturn(false);
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenThrow(new DataIntegrityViolationException("unique (application_id, author_role) 위반"));
        WriteReviewRequest req = new WriteReviewRequest();
        req.setRating(5);

        // when & then — 500 이 아닌 사용자 친화적 409 로 변환되어야 한다
        assertThatThrownBy(() -> reviewService.writeReview(10, 7, Role.CREATOR, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS);
    }
}

package com.viralground.backend.service;

import com.viralground.backend.entity.*;
import com.viralground.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock SubmissionMetricRepository metricRepository;
    @Mock CreatorProfileRepository creatorProfileRepository;

    @InjectMocks
    PortfolioService portfolioService;

    private Member creator() {
        Member m = Member.builder()
                .id(7)
                .email("c@vg.test")
                .password("pw")
                .name("크리에이터")
                .role(Role.CREATOR)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build();
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private CampaignApplication settledApp(int id) {
        return CampaignApplication.builder()
                .id(id)
                .campaignId(100)
                .creatorId(7)
                .status(ApplicationStatus.SETTLED)
                .rewardPaidAmount(10_000)
                .settledAt(LocalDateTime.now())
                .build();
    }

    @Test
    void 크리에이터_집계_조회수가_summary_에_포함된다() {
        // given — metric 이 2건 쌓였고 조회수 합 5000, 좋아요 150, 댓글 20 이라고 하자.
        when(memberRepository.findById(7)).thenReturn(Optional.of(creator()));
        when(applicationRepository.findByCreatorIdOrderByAppliedAtDesc(7))
                .thenReturn(List.of(settledApp(1), settledApp(2)));
        when(campaignRepository.findAllById(List.of(100))).thenReturn(List.of());
        when(reviewRepository.findByTargetIdOrderByCreatedAtDesc(7)).thenReturn(List.of());
        when(metricRepository.sumByCreatorId(7))
                .thenReturn(new Object[]{5000L, 150L, 20L, 2L});

        // when
        Map<String, Object> portfolio = portfolioService.getPortfolio(7);

        // then
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) portfolio.get("summary");
        assertThat(summary).containsEntry("totalViews", 5000L);
        assertThat(summary).containsEntry("totalLikes", 150L);
        assertThat(summary).containsEntry("totalComments", 20L);
        assertThat(summary).containsEntry("metricSampleSize", 2L);
        // 5000 / 2 = 2500
        assertThat(summary).containsEntry("averageViews", 2500L);
    }

    @Test
    void metric_이_없으면_averageViews_는_0_이다() {
        // given — 집계 결과가 모두 0 이고 샘플도 0
        when(memberRepository.findById(7)).thenReturn(Optional.of(creator()));
        when(applicationRepository.findByCreatorIdOrderByAppliedAtDesc(7)).thenReturn(List.of());
        when(reviewRepository.findByTargetIdOrderByCreatedAtDesc(7)).thenReturn(List.of());
        when(metricRepository.sumByCreatorId(7))
                .thenReturn(new Object[]{0L, 0L, 0L, 0L});

        // when
        Map<String, Object> portfolio = portfolioService.getPortfolio(7);

        // then
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) portfolio.get("summary");
        assertThat(summary).containsEntry("averageViews", 0L);
        assertThat(summary).containsEntry("metricSampleSize", 0L);
    }

    @Test
    void SETTLED_지원이_없어도_기존_카드는_0_으로_렌더된다() {
        // given — 리팩터 회귀 가드. 기존 totalCompleted/reviewCount/averageRating 이 여전히 노출.
        when(memberRepository.findById(7)).thenReturn(Optional.of(creator()));
        when(applicationRepository.findByCreatorIdOrderByAppliedAtDesc(7)).thenReturn(List.of());
        when(reviewRepository.findByTargetIdOrderByCreatedAtDesc(7)).thenReturn(List.of());
        when(metricRepository.sumByCreatorId(7))
                .thenReturn(new Object[]{0L, 0L, 0L, 0L});

        // when
        Map<String, Object> portfolio = portfolioService.getPortfolio(7);

        // then
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) portfolio.get("summary");
        assertThat(summary).containsEntry("totalCompleted", 0);
        assertThat(summary).containsEntry("reviewCount", 0);
        assertThat(summary).containsEntry("averageRating", 0.0);
        assertThat((List<?>) portfolio.get("items")).isEmpty();
    }

    @Test
    void publicPortfolioIsNotAvailableWithoutExplicitDirectoryOptIn() {
        when(creatorProfileRepository.findByMemberId(7)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> portfolioService.getPublicPortfolio(7))
                .isInstanceOf(com.viralground.backend.exception.AppException.class)
                .extracting("errorCode")
                .isEqualTo(com.viralground.backend.exception.ErrorCode.USER_NOT_FOUND);
    }
}

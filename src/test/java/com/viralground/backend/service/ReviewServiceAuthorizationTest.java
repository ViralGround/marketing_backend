package com.viralground.backend.service;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.Role;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.ReviewRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceAuthorizationTest {
    @Mock ReviewRepository reviewRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock MemberRepository memberRepository;
    @Mock CreatorProfileRepository creatorProfileRepository;
    @InjectMocks ReviewService service;

    @Test
    void unrelatedAuthenticatedMemberCannotReadApplicationReviews() {
        stubParticipants(12, 40, 90);

        assertThatThrownBy(() -> service.getReviewsOfApplication(12, 777, Role.CREATOR))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(reviewRepository, memberRepository);
    }

    @Test
    void applicationCreatorCanReadReviews() {
        stubParticipants(12, 40, 90);
        when(reviewRepository.findByApplicationIdOrderByCreatedAtAsc(12)).thenReturn(List.of());

        assertThatCode(() -> service.getReviewsOfApplication(12, 40, Role.CREATOR))
                .doesNotThrowAnyException();
    }

    @Test
    void campaignCompanyCanReadReviews() {
        stubParticipants(12, 40, 90);
        when(reviewRepository.findByApplicationIdOrderByCreatedAtAsc(12)).thenReturn(List.of());

        assertThatCode(() -> service.getReviewsOfApplication(12, 90, Role.COMPANY))
                .doesNotThrowAnyException();
    }

    @Test
    void publicCreatorReviewsRequireExplicitDirectoryOptIn() {
        when(memberRepository.findById(40)).thenReturn(Optional.of(Member.builder()
                .id(40).role(Role.CREATOR).status(MemberStatus.APPROVED).build()));
        when(creatorProfileRepository.findByMemberId(40)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublicReviewsReceivedBy(40))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        verifyNoInteractions(reviewRepository);
    }

    private void stubParticipants(int applicationId, int creatorId, int companyId) {
        CampaignApplication application = CampaignApplication.builder()
                .id(applicationId).campaignId(8).creatorId(creatorId).build();
        Campaign campaign = Campaign.builder().id(8).createdById(companyId).build();
        when(applicationRepository.findById(applicationId)).thenReturn(Optional.of(application));
        when(campaignRepository.findById(8)).thenReturn(Optional.of(campaign));
    }
}

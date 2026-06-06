package com.viralground.backend.service;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceSetFeaturedTest {

    @Mock MemberRepository memberRepository;
    @Mock CreatorProfileRepository profileRepository;
    @Mock CompanyProfileRepository companyProfileRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock EscrowTransactionRepository escrowTransactionRepository;
    @Mock EmailService emailService;
    @Mock EscrowService escrowService;
    @Mock ApplicationSubmissionRepository submissionRepository;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    AdminService adminService;

    Campaign campaign;

    @BeforeEach
    void setUp() {
        campaign = Campaign.builder()
                .id(1)
                .status(CampaignStatus.OPEN)
                .escrowStatus(EscrowStatus.FUNDED)
                .build();
    }

    @Test
    void should_featuredOrder_채번_when_featured_true() {
        // given — 노출 중 대표 2건(한도 OK), 기존 최대 순번 2 → 새 지정은 순번 3
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(campaignRepository.countFeaturedOpen(any())).thenReturn(2L);
        when(campaignRepository.maxFeaturedOrder()).thenReturn(2);

        // when
        adminService.setCampaignFeatured(1, true);

        // then
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getFeaturedOrder()).isEqualTo(3);
        assertThat(captor.getValue().isFeatured()).isTrue();
    }

    @Test
    void should_featuredOrder_null_when_featured_false() {
        // given — 이미 대표인 캠페인 해제
        campaign.setFeaturedOrder(2);
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));

        // when
        adminService.setCampaignFeatured(1, false);

        // then
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getFeaturedOrder()).isNull();
        assertThat(captor.getValue().isFeatured()).isFalse();
    }

    @Test
    void should_FEATURED_LIMIT_EXCEEDED_when_이미_노출_3건() {
        // given — 노출 중 대표가 이미 3건이면 4번째 지정 거부
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(campaignRepository.countFeaturedOpen(any())).thenReturn(3L);

        // when & then
        assertThatThrownBy(() -> adminService.setCampaignFeatured(1, true))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FEATURED_LIMIT_EXCEEDED);
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void should_이미_대표면_3건이어도_재지정_허용() {
        // given — 이미 대표(featuredOrder!=null)인 건의 재토글은 한도 검증 대상 아님(멱등)
        campaign.setFeaturedOrder(1);
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));

        // when
        adminService.setCampaignFeatured(1, true);

        // then — 한도 카운트/채번 조회 없이 그대로 유지 저장
        verify(campaignRepository, never()).countFeaturedOpen(any());
        verify(campaignRepository, never()).maxFeaturedOrder();
        verify(campaignRepository).save(any());
        assertThat(campaign.getFeaturedOrder()).isEqualTo(1);
    }

    @Test
    void should_CAMPAIGN_NOT_FOUND_when_존재하지_않는_id() {
        // given
        when(campaignRepository.findById(99)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminService.setCampaignFeatured(99, true))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND);
    }
}

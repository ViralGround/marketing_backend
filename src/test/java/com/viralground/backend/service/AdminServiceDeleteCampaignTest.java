package com.viralground.backend.service;

import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceDeleteCampaignTest {

    @Mock MemberRepository memberRepository;
    @Mock CreatorProfileRepository profileRepository;
    @Mock CompanyProfileRepository companyProfileRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock EscrowTransactionRepository escrowTransactionRepository;
    @Mock EmailService emailService;
    @Mock EscrowService escrowService;
    @Mock ApplicationSubmissionRepository submissionRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks
    AdminService adminService;

    Campaign campaign;

    @BeforeEach
    void setUp() {
        campaign = Campaign.builder()
                .id(1)
                .status(CampaignStatus.DRAFT)
                .escrowStatus(EscrowStatus.NONE)
                .build();
    }

    @Test
    void should_CAMPAIGN_HAS_DEPENDENCIES_예외_when_지원자가_있는_캠페인_삭제() {
        // given
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(applicationRepository.countByCampaignId(1)).thenReturn(1L);

        // when & then
        assertThatThrownBy(() -> adminService.deleteCampaign(1))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_HAS_DEPENDENCIES);
        verify(campaignRepository, never()).delete(any());
        verify(campaignRepository, never()).deleteById(any());
    }

    @Test
    void should_CAMPAIGN_HAS_DEPENDENCIES_예외_when_에스크로_트랜잭션이_있는_캠페인_삭제() {
        // given — 지원자는 0명이지만 에스크로 트랜잭션이 1건 (예: DEPOSIT) 남아있음
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(applicationRepository.countByCampaignId(1)).thenReturn(0L);
        when(escrowTransactionRepository.countByCampaignId(1)).thenReturn(1L);

        // when & then
        assertThatThrownBy(() -> adminService.deleteCampaign(1))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_HAS_DEPENDENCIES);
        verify(campaignRepository, never()).delete(any());
        verify(campaignRepository, never()).deleteById(any());
    }

    @Test
    void should_정상_삭제_when_자식_데이터_없음() {
        // given
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(applicationRepository.countByCampaignId(1)).thenReturn(0L);
        when(escrowTransactionRepository.countByCampaignId(1)).thenReturn(0L);

        // when
        adminService.deleteCampaign(1);

        // then
        verify(campaignRepository).delete(campaign);
    }

    @Test
    void should_CAMPAIGN_NOT_FOUND_예외_when_존재하지_않는_id() {
        // given
        when(campaignRepository.findById(99)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminService.deleteCampaign(99))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND);
    }
}

package com.viralground.backend.service;

import com.viralground.backend.dto.admin.UpdateCampaignAdminRequest;
import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import org.springframework.context.ApplicationEventPublisher;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceUpdateCampaignTest {

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
    @Mock com.viralground.backend.storage.FileStorage fileStorage;

    @InjectMocks
    AdminService adminService;

    Campaign campaign;

    @BeforeEach
    void setUp() {
        campaign = Campaign.builder()
                .id(1)
                .rewardAmount(10_000)
                .maxParticipants(5)
                .totalBudget(50_000)
                .status(CampaignStatus.DRAFT)
                .escrowStatus(EscrowStatus.PENDING_DEPOSIT)
                .build();
    }

    @Test
    void should_totalBudget_재계산_when_rewardAmount_변경() {
        // given
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        UpdateCampaignAdminRequest req = new UpdateCampaignAdminRequest();
        req.setRewardAmount(20_000); // 20000 * 5 = 100000

        // when
        adminService.updateCampaign(1, req);

        // then
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalBudget()).isEqualTo(100_000);
        assertThat(captor.getValue().getRewardAmount()).isEqualTo(20_000);
    }

    @Test
    void should_totalBudget_재계산_when_maxParticipants_변경() {
        // given
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        UpdateCampaignAdminRequest req = new UpdateCampaignAdminRequest();
        req.setMaxParticipants(10); // 10000 * 10 = 100000

        // when
        adminService.updateCampaign(1, req);

        // then
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalBudget()).isEqualTo(100_000);
        assertThat(captor.getValue().getMaxParticipants()).isEqualTo(10);
    }

    @Test
    void should_INVALID_ESCROW_STATE_예외_when_예치_진행된_캠페인_예산_변경() {
        // given — FUNDED 상태에서는 보상 금액이나 인원수 변경 불가
        campaign.setEscrowStatus(EscrowStatus.FUNDED);
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        UpdateCampaignAdminRequest req = new UpdateCampaignAdminRequest();
        req.setRewardAmount(20_000);

        // when & then
        assertThatThrownBy(() -> adminService.updateCampaign(1, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ESCROW_STATE);
    }

    @Test
    void should_totalBudget_유지_when_예산_무관_필드만_변경() {
        // given
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        UpdateCampaignAdminRequest req = new UpdateCampaignAdminRequest();
        req.setTitle("새 제목");

        // when
        adminService.updateCampaign(1, req);

        // then — totalBudget 은 그대로
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalBudget()).isEqualTo(50_000);
        assertThat(captor.getValue().getTitle()).isEqualTo("새 제목");
    }

    @Test
    void should_브랜드_소개와_로고_갱신_when_전달됨() {
        // given
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(fileStorage.exists("thumbnails/brand-1")).thenReturn(true);
        UpdateCampaignAdminRequest req = new UpdateCampaignAdminRequest();
        req.setBrandIntroduction("새 브랜드 소개");
        req.setBrandLogoFileKey("thumbnails/brand-1");

        // when
        adminService.updateCampaign(1, req);

        // then
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getBrandIntroduction()).isEqualTo("새 브랜드 소개");
        assertThat(captor.getValue().getBrandLogoFileKey()).isEqualTo("thumbnails/brand-1");
    }

    @Test
    void should_브랜드로고_제거_when_빈문자열() {
        // given — 빈 문자열은 로고 제거
        campaign.setBrandLogoFileKey("thumbnails/old");
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        UpdateCampaignAdminRequest req = new UpdateCampaignAdminRequest();
        req.setBrandLogoFileKey("");

        // when
        adminService.updateCampaign(1, req);

        // then
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getBrandLogoFileKey()).isNull();
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_when_예산_곱셈이_int_범위를_초과() {
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        UpdateCampaignAdminRequest req = new UpdateCampaignAdminRequest();
        req.setRewardAmount(100_000_000);
        req.setMaxParticipants(10_000);

        assertThatThrownBy(() -> adminService.updateCampaign(1, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
    }
}

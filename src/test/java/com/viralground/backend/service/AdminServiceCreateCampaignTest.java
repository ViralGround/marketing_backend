package com.viralground.backend.service;

import com.viralground.backend.dto.campaign.CampaignCreateRequest;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import com.viralground.backend.storage.FileStorage;
import com.viralground.backend.payment.PaymentActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceCreateCampaignTest {

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
    @Mock ReviewRepository reviewRepository;
    @Mock SubmissionMetricRepository metricRepository;
    @Mock FileStorage fileStorage;

    @InjectMocks
    AdminService adminService;

    @BeforeEach
    void enableUploads() {
        ReflectionTestUtils.setField(adminService, "uploadsFeatureEnabled", true);
    }

    private CampaignCreateRequest baseReq() {
        CampaignCreateRequest req = new CampaignCreateRequest();
        req.setTitle("t");
        req.setDescription("d");
        req.setBrandName("b");
        req.setRewardAmount(10_000);
        req.setMaxParticipants(5);
        req.setImmediatelyOpen(false); // 에스크로 트랜잭션 생성 분기를 피해 검증을 단순화
        return req;
    }

    @Test
    void should_브랜드_소개와_로고_저장_when_캠페인_생성() {
        // given
        CampaignCreateRequest req = baseReq();
        req.setBrandIntroduction("브랜드 소개");
        req.setBrandLogoFileKey("thumbnails/brand-1");
        when(fileStorage.exists("thumbnails/brand-1")).thenReturn(true);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // when
        adminService.createCampaign(req, 1);

        // then
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getBrandIntroduction()).isEqualTo("브랜드 소개");
        assertThat(captor.getValue().getBrandLogoFileKey()).isEqualTo("thumbnails/brand-1");
    }

    @Test
    void should_SUBMISSION_NOT_FOUND_when_브랜드로고_파일이_없음() {
        // given
        CampaignCreateRequest req = baseReq();
        req.setBrandLogoFileKey("thumbnails/missing");
        when(fileStorage.exists("thumbnails/missing")).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> adminService.createCampaign(req, 1))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBMISSION_NOT_FOUND);
    }

    @Test
    void should_결제검증_경로를_통해서만_즉시오픈_when_immediatelyOpen_true() {
        ReflectionTestUtils.setField(adminService, "paymentsFeatureEnabled", true);
        CampaignCreateRequest req = baseReq();
        req.setImmediatelyOpen(true);
        when(campaignRepository.save(any())).thenAnswer(inv -> {
            Campaign campaign = inv.getArgument(0);
            campaign.setId(77);
            return campaign;
        });

        Campaign saved = adminService.createCampaign(req, 1);

        assertThat(saved.getStatus()).isEqualTo(com.viralground.backend.entity.CampaignStatus.DRAFT);
        assertThat(saved.getEscrowStatus()).isEqualTo(com.viralground.backend.entity.EscrowStatus.PENDING_DEPOSIT);
        verify(escrowService).forceConfirmDeposit(
                77, PaymentActor.admin(1), "관리자 캠페인 즉시 오픈", "deposit:campaign:77");
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    void should_create_open_nonfinancial_campaign_without_gateway_when_payments_disabled() {
        ReflectionTestUtils.setField(adminService, "paymentsFeatureEnabled", false);
        CampaignCreateRequest req = baseReq();
        req.setImmediatelyOpen(true);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Campaign saved = adminService.createCampaign(req, 1);

        assertThat(saved.getStatus())
                .isEqualTo(com.viralground.backend.entity.CampaignStatus.OPEN);
        assertThat(saved.getEscrowStatus())
                .isEqualTo(com.viralground.backend.entity.EscrowStatus.NONE);
        verify(escrowService, never()).forceConfirmDeposit(any(), any(), any(), any());
        verify(escrowTransactionRepository, never()).save(any());
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_when_보상금이_0() {
        CampaignCreateRequest req = baseReq();
        req.setRewardAmount(0);

        assertThatThrownBy(() -> adminService.createCampaign(req, 1))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
        verify(campaignRepository, never()).save(any());
    }
}

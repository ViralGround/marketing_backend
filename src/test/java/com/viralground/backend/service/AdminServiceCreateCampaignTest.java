package com.viralground.backend.service;

import com.viralground.backend.dto.campaign.CampaignCreateRequest;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import com.viralground.backend.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
}

package com.viralground.backend.service;

import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import com.viralground.backend.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
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
    @Mock ReviewRepository reviewRepository;
    @Mock SubmissionMetricRepository metricRepository;
    @Mock FileStorage fileStorage;

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
    void should_CAMPAIGN_HAS_SETTLEMENT_예외_when_실지급_이력이_있는_캠페인_삭제() {
        // given — RELEASE(실지급) 트랜잭션이 존재하면 회계 보존을 위해 삭제 거부
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(escrowTransactionRepository.existsByCampaignIdAndType(1, EscrowTxType.RELEASE)).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> adminService.deleteCampaign(1))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_HAS_SETTLEMENT);
        verify(campaignRepository, never()).delete(any());
    }

    @Test
    void should_연쇄_삭제_when_실지급_없이_지원과_예치금만_있는_캠페인() {
        // given — DEPOSIT 만 있고 RELEASE 는 없음. 지원 1건(영상 파일)과 썸네일이 있음
        CampaignApplication app = CampaignApplication.builder()
                .id(10).campaignId(1).creatorId(7)
                .videoFileKey("submissions/v.mp4").build();
        campaign.setThumbnailFileKey("thumbnails/t.webp");
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(escrowTransactionRepository.existsByCampaignIdAndType(1, EscrowTxType.RELEASE)).thenReturn(false);
        when(applicationRepository.findByCampaignIdOrderByAppliedAtDesc(1)).thenReturn(List.of(app));

        // when
        adminService.deleteCampaign(1);

        // then — 지원에 딸린 자식부터 캠페인까지 연쇄 삭제하고 업로드 파일도 정리
        verify(submissionRepository).deleteByApplicationIdIn(List.of(10));
        verify(metricRepository).deleteByApplicationIdIn(List.of(10));
        verify(reviewRepository).deleteByApplicationIdIn(List.of(10));
        verify(applicationRepository).deleteByCampaignId(1);
        verify(escrowTransactionRepository).deleteByCampaignId(1);
        verify(fileStorage).delete("submissions/v.mp4");
        verify(fileStorage).delete("thumbnails/t.webp");
        verify(campaignRepository).delete(campaign);
    }

    @Test
    void should_단순_삭제_when_자식_데이터_없음() {
        // given — 지원/트랜잭션 없음
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(escrowTransactionRepository.existsByCampaignIdAndType(1, EscrowTxType.RELEASE)).thenReturn(false);
        when(applicationRepository.findByCampaignIdOrderByAppliedAtDesc(1)).thenReturn(List.of());

        // when
        adminService.deleteCampaign(1);

        // then — application 단위 자식 삭제는 호출하지 않고 캠페인/트랜잭션만 정리
        verify(submissionRepository, never()).deleteByApplicationIdIn(any());
        verify(metricRepository, never()).deleteByApplicationIdIn(any());
        verify(reviewRepository, never()).deleteByApplicationIdIn(any());
        verify(applicationRepository).deleteByCampaignId(1);
        verify(escrowTransactionRepository).deleteByCampaignId(1);
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

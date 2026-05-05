package com.viralground.backend.service;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.ApplicationSubmissionRepository;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampaignServiceGetCampaignTest {

    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock EmailService emailService;
    @Mock MemberRepository memberRepository;
    @Mock ApplicationSubmissionRepository submissionRepository;
    @Mock FileStorage fileStorage;

    @InjectMocks
    CampaignService campaignService;

    Campaign hidden;

    @BeforeEach
    void setUp() {
        hidden = Campaign.builder()
                .id(10)
                .title("숨김 캠페인")
                .description("desc")
                .brandName("브랜드")
                .rewardAmount(10_000)
                .totalBudget(50_000)
                .escrowStatus(EscrowStatus.FUNDED)
                .maxParticipants(5)
                .status(CampaignStatus.OPEN)
                .createdById(1)
                .hiddenAt(LocalDateTime.now())
                .build();
    }

    @Test
    void should_숨김_캠페인_상세_404_when_지원자() {
        // given — 본인 지원이 있어도 숨김이면 차단 (정책: 전원 차단)
        when(campaignRepository.findById(10)).thenReturn(Optional.of(hidden));

        // when & then
        assertThatThrownBy(() -> campaignService.getCampaign(10, 99))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND);
    }

    @Test
    void should_숨김_캠페인_상세_404_when_비로그인() {
        // given
        when(campaignRepository.findById(10)).thenReturn(Optional.of(hidden));

        // when & then
        assertThatThrownBy(() -> campaignService.getCampaign(10, null))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND);
    }

    @Test
    void should_숨김_캠페인_지원_거부() {
        // given
        when(campaignRepository.findById(10)).thenReturn(Optional.of(hidden));

        // when & then
        assertThatThrownBy(() -> campaignService.apply(10, 99, "msg"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND);
    }
}

package com.viralground.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viralground.backend.dto.campaign.ApplicationResponse;
import com.viralground.backend.dto.campaign.CampaignResponse;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignServiceGetCampaignTest {

    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock EmailService emailService;
    @Mock MemberRepository memberRepository;
    @Mock ApplicationSubmissionRepository submissionRepository;
    @Mock FileStorage fileStorage;
    @Spy Clock clock = Clock.systemDefaultZone();

    @InjectMocks
    CampaignService campaignService;

    Campaign hidden;
    Campaign expired;
    Member approvedCreator;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(campaignService, "paymentsFeatureEnabled", true);
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
        expired = Campaign.builder()
                .id(20)
                .title("마감된 캠페인")
                .description("desc")
                .brandName("브랜드")
                .rewardAmount(10_000)
                .totalBudget(50_000)
                .escrowStatus(EscrowStatus.FUNDED)
                .maxParticipants(5)
                .status(CampaignStatus.OPEN)
                .createdById(1)
                .deadline(LocalDateTime.now().minusDays(1))
                .build();
        approvedCreator = Member.builder()
                .id(99)
                .email("creator@example.com")
                .name("크리에이터")
                .role(Role.CREATOR)
                .status(MemberStatus.APPROVED)
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
        when(memberRepository.findById(99)).thenReturn(Optional.of(approvedCreator));
        when(campaignRepository.findByIdForUpdate(10)).thenReturn(Optional.of(hidden));

        // when & then
        assertThatThrownBy(() -> campaignService.apply(10, 99, "msg"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND);
    }

    @Test
    void should_마감_지난_캠페인_상세_404() {
        // given — deadline 이 과거인 캠페인은 직링크로도 진입 불가 (정보 누출 방지)
        when(campaignRepository.findById(20)).thenReturn(Optional.of(expired));

        // when & then
        assertThatThrownBy(() -> campaignService.getCampaign(20, 99))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND);
    }

    @Test
    void should_마감_지난_캠페인_지원_거부() {
        // given — 마감 후 지원 시도는 CAMPAIGN_CLOSED 로 거부 (상세는 404 와 다른 코드로 분리)
        when(memberRepository.findById(99)).thenReturn(Optional.of(approvedCreator));
        when(campaignRepository.findByIdForUpdate(20)).thenReturn(Optional.of(expired));

        // when & then
        assertThatThrownBy(() -> campaignService.apply(20, 99, "msg"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_CLOSED);
    }

    @Test
    void should_omit_monetary_fields_from_creator_responses_when_payments_disabled() throws Exception {
        ReflectionTestUtils.setField(campaignService, "paymentsFeatureEnabled", false);
        Campaign open = Campaign.builder()
                .id(31).title("비거래형 캠페인").description("desc").brandName("브랜드")
                .rewardAmount(500_000).totalBudget(1_000_000).maxParticipants(2)
                .escrowStatus(EscrowStatus.NONE).status(CampaignStatus.OPEN)
                .createdById(1).deadline(LocalDateTime.now().plusDays(5)).build();
        when(campaignRepository.findById(31)).thenReturn(Optional.of(open));
        when(applicationRepository.findByCampaignIdAndCreatorId(31, 99)).thenReturn(Optional.empty());
        when(applicationRepository.countByCampaignId(31)).thenReturn(0L);

        CampaignResponse campaign = campaignService.getCampaign(31, 99);
        CampaignApplication application = CampaignApplication.builder()
                .id(9).campaignId(31).creatorId(99).campaign(open)
                .rewardPaidAmount(500_000).build();
        ApplicationResponse applicationResponse = new ApplicationResponse(application, false);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        assertThat(campaign.getRewardAmount()).isNull();
        assertThat(applicationResponse.getRewardPaidAmount()).isNull();
        assertThat(applicationResponse.getCampaign().rewardAmount()).isNull();
        assertThat(mapper.writeValueAsString(campaign)).doesNotContain("rewardAmount");
        assertThat(mapper.writeValueAsString(applicationResponse))
                .doesNotContain("rewardPaidAmount")
                .doesNotContain("rewardAmount");
    }

    @Test
    void should_omit_total_earned_and_skip_financial_query_when_payments_disabled() {
        ReflectionTestUtils.setField(campaignService, "paymentsFeatureEnabled", false);
        when(applicationRepository.countCompletedByCreatorId(99)).thenReturn(2L);
        when(applicationRepository.countByCreatorIdAndStatus(99,
                com.viralground.backend.entity.ApplicationStatus.APPROVED)).thenReturn(1L);
        when(applicationRepository.countByCreatorIdAndStatus(99,
                com.viralground.backend.entity.ApplicationStatus.SUBMITTED)).thenReturn(1L);
        when(applicationRepository.findByCreatorIdOrderByAppliedAtDesc(99)).thenReturn(List.of());

        var stats = campaignService.getStats(99);

        assertThat(stats).doesNotContainKey("totalEarned");
        verify(applicationRepository, never()).sumRewardByCreatorId(anyInt());
    }

    @Test
    void should_allow_application_to_open_none_escrow_campaign_only_in_managed_beta() {
        ReflectionTestUtils.setField(campaignService, "paymentsFeatureEnabled", false);
        Campaign managedBeta = Campaign.builder()
                .id(30).title("관리형 베타").description("desc").brandName("브랜드")
                .rewardAmount(10_000).totalBudget(50_000).maxParticipants(5)
                .escrowStatus(EscrowStatus.NONE).status(CampaignStatus.OPEN)
                .createdById(1).deadline(LocalDateTime.now().plusDays(5)).build();
        when(memberRepository.findById(99)).thenReturn(Optional.of(approvedCreator));
        when(campaignRepository.findByIdForUpdate(30)).thenReturn(Optional.of(managedBeta));
        when(applicationRepository.existsByCampaignIdAndCreatorId(30, 99)).thenReturn(false);
        when(applicationRepository.countByCampaignId(30)).thenReturn(0L);
        when(applicationRepository.save(any(CampaignApplication.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CampaignApplication application = campaignService.apply(30, 99, "지원합니다");

        assertThat(application.getCampaignId()).isEqualTo(30);
        assertThat(application.getCreatorId()).isEqualTo(99);
        verify(emailService).notifyAdminsOfNewApplication("관리형 베타", "크리에이터");
    }

    @Test
    void should_reject_none_escrow_campaign_when_payments_enabled() {
        Campaign invalidLegacy = Campaign.builder()
                .id(30).title("잘못된 상태").description("desc").brandName("브랜드")
                .rewardAmount(10_000).totalBudget(50_000).maxParticipants(5)
                .escrowStatus(EscrowStatus.NONE).status(CampaignStatus.OPEN)
                .createdById(1).deadline(LocalDateTime.now().plusDays(5)).build();
        when(memberRepository.findById(99)).thenReturn(Optional.of(approvedCreator));
        when(campaignRepository.findByIdForUpdate(30)).thenReturn(Optional.of(invalidLegacy));

        assertThatThrownBy(() -> campaignService.apply(30, 99, "지원합니다"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_NOT_FUNDED);
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void should_reject_funded_legacy_campaign_when_payments_disabled() {
        ReflectionTestUtils.setField(campaignService, "paymentsFeatureEnabled", false);
        Campaign fundedLegacy = Campaign.builder()
                .id(32).title("legacy funded").description("desc").brandName("브랜드")
                .rewardAmount(10_000).totalBudget(50_000).maxParticipants(5)
                .escrowStatus(EscrowStatus.FUNDED).status(CampaignStatus.OPEN)
                .createdById(1).deadline(LocalDateTime.now().plusDays(5)).build();
        when(memberRepository.findById(99)).thenReturn(Optional.of(approvedCreator));
        when(campaignRepository.findByIdForUpdate(32)).thenReturn(Optional.of(fundedLegacy));

        assertThatThrownBy(() -> campaignService.apply(32, 99, "지원합니다"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_NOT_FUNDED);
        verify(applicationRepository, never()).save(any());
    }
}

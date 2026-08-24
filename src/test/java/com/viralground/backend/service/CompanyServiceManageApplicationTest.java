package com.viralground.backend.service;

import com.viralground.backend.dto.company.CompanyApplicationActionRequest;
import com.viralground.backend.dto.company.CompanyApplicationActionRequest.Action;
import com.viralground.backend.entity.*;
import com.viralground.backend.event.ApplicationResultEvent;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.ApplicationSubmissionRepository;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.EscrowTransactionRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.payment.PaymentActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import com.viralground.backend.storage.UploadOwnershipService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyServiceManageApplicationTest {

    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock MemberRepository memberRepository;
    @Mock EscrowService escrowService;
    @Mock EscrowTransactionRepository escrowTransactionRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock UploadOwnershipService uploadOwnershipService;
    @Mock ApplicationSubmissionRepository submissionRepository;

    @InjectMocks
    CompanyService companyService;

    CampaignApplication submittedApp;
    Campaign campaign;
    Member creator;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(companyService, "paymentsFeatureEnabled", true);
        campaign = Campaign.builder()
                .id(1).createdById(50).rewardAmount(30_000).build();
        submittedApp = CampaignApplication.builder()
                .id(10).campaignId(1).creatorId(7)
                .status(ApplicationStatus.SUBMITTED)
                .resubmissionCount(0)
                .videoFileKey("submissions/a.mp4")
                .build();
        creator = Member.builder().id(7).email("c@c.com").name("크리").build();
    }

    @Test
    void should_CHANGES_REQUESTED_전환_when_REQUEST_CHANGES() {
        // given
        when(applicationRepository.findByIdForUpdate(10)).thenReturn(Optional.of(submittedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(memberRepository.findById(7)).thenReturn(Optional.of(creator));
        when(submissionRepository.findTopByApplicationIdOrderBySubmittedAtDesc(10))
                .thenReturn(Optional.of(ApplicationSubmission.builder()
                        .id(99).applicationId(10).status(SubmissionReviewStatus.SUBMITTED).build()));
        CompanyApplicationActionRequest req = new CompanyApplicationActionRequest();
        req.setAction(Action.REQUEST_CHANGES);
        req.setReviewComment("로고 노출 부족");

        // when
        companyService.manageApplication(10, 50, req);

        // then
        assertThat(submittedApp.getStatus()).isEqualTo(ApplicationStatus.CHANGES_REQUESTED);
        assertThat(submittedApp.getReviewComment()).isEqualTo("로고 노출 부족");

        // 최신 submission 도 CHANGES_REQUESTED 로 업데이트
        ArgumentCaptor<ApplicationSubmission> sc = ArgumentCaptor.forClass(ApplicationSubmission.class);
        verify(submissionRepository).save(sc.capture());
        assertThat(sc.getValue().getStatus()).isEqualTo(SubmissionReviewStatus.CHANGES_REQUESTED);
        assertThat(sc.getValue().getReviewComment()).isEqualTo("로고 노출 부족");

        // 이메일 이벤트 발행 (상태 문자열)
        ArgumentCaptor<ApplicationResultEvent> ec = ArgumentCaptor.forClass(ApplicationResultEvent.class);
        verify(eventPublisher).publishEvent(ec.capture());
        assertThat(ec.getValue().status()).isEqualTo("CHANGES_REQUESTED");
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_예외_when_REQUEST_CHANGES_사유_누락() {
        // given
        when(applicationRepository.findByIdForUpdate(10)).thenReturn(Optional.of(submittedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        CompanyApplicationActionRequest req = new CompanyApplicationActionRequest();
        req.setAction(Action.REQUEST_CHANGES);
        req.setReviewComment(""); // 빈 문자열

        // when & then
        assertThatThrownBy(() -> companyService.manageApplication(10, 50, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
    }

    @Test
    void should_SETTLED_전환_및_release_호출_when_APPROVE_VIDEO() {
        // given
        when(applicationRepository.findByIdForUpdate(10)).thenReturn(Optional.of(submittedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(memberRepository.findById(7)).thenReturn(Optional.of(creator));
        when(submissionRepository.findTopByApplicationIdOrderBySubmittedAtDesc(10))
                .thenReturn(Optional.of(ApplicationSubmission.builder()
                        .id(99).applicationId(10).status(SubmissionReviewStatus.SUBMITTED).build()));
        CompanyApplicationActionRequest req = new CompanyApplicationActionRequest();
        req.setAction(Action.APPROVE_VIDEO);

        // when
        companyService.manageApplication(10, 50, req);

        // then
        verify(escrowService).release(eq(1), eq(10), eq(30_000),
                eq(PaymentActor.company(50)), eq("기업 콘텐츠 승인 및 정산"),
                eq("release:campaign:1:application:10"));
        assertThat(submittedApp.getStatus()).isEqualTo(ApplicationStatus.SETTLED);
        assertThat(submittedApp.getRewardPaidAmount()).isEqualTo(30_000);
        assertThat(submittedApp.getContentApprovedAt()).isNull();
        assertThat(submittedApp.getSettledAt()).isNotNull();
    }

    @Test
    void should_REJECTED_전환_when_REJECT_VIDEO() {
        // given
        when(applicationRepository.findByIdForUpdate(10)).thenReturn(Optional.of(submittedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(memberRepository.findById(7)).thenReturn(Optional.of(creator));
        when(submissionRepository.findTopByApplicationIdOrderBySubmittedAtDesc(10))
                .thenReturn(Optional.of(ApplicationSubmission.builder()
                        .id(99).applicationId(10).status(SubmissionReviewStatus.SUBMITTED).build()));
        CompanyApplicationActionRequest req = new CompanyApplicationActionRequest();
        req.setAction(Action.REJECT_VIDEO);
        req.setReviewComment("브랜드 가이드라인 불일치");

        // when
        companyService.manageApplication(10, 50, req);

        // then
        assertThat(submittedApp.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        verify(escrowService, never()).release(anyInt(), anyInt(), anyInt(), any(), anyString(), anyString());
        ArgumentCaptor<ApplicationSubmission> sc = ArgumentCaptor.forClass(ApplicationSubmission.class);
        verify(submissionRepository).save(sc.capture());
        assertThat(sc.getValue().getStatus()).isEqualTo(SubmissionReviewStatus.REJECTED);
    }

    @Test
    void should_COMPLETED_without_payment_mutation_when_APPROVE_CONTENT_in_managed_beta() {
        ReflectionTestUtils.setField(companyService, "paymentsFeatureEnabled", false);
        campaign.setEscrowStatus(EscrowStatus.NONE);
        submittedApp.setRewardPaidAmount(null);
        submittedApp.setSettledAt(null);
        when(applicationRepository.findByIdForUpdate(10)).thenReturn(Optional.of(submittedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        when(memberRepository.findById(7)).thenReturn(Optional.of(creator));
        when(submissionRepository.findTopByApplicationIdOrderBySubmittedAtDesc(10))
                .thenReturn(Optional.of(ApplicationSubmission.builder()
                        .id(99).applicationId(10).status(SubmissionReviewStatus.SUBMITTED).build()));
        CompanyApplicationActionRequest req = new CompanyApplicationActionRequest();
        req.setAction(Action.APPROVE_CONTENT);

        companyService.manageApplication(10, 50, req);

        assertThat(submittedApp.getStatus()).isEqualTo(ApplicationStatus.SETTLED);
        assertThat(submittedApp.getApiStatus()).isEqualTo("COMPLETED");
        assertThat(submittedApp.getContentApprovedAt()).isNotNull();
        assertThat(submittedApp.getRewardPaidAmount()).isNull();
        assertThat(submittedApp.getSettledAt()).isNull();
        assertThat(submittedApp.getReviewedAt()).isNotNull();
        verifyNoInteractions(escrowService);
        ArgumentCaptor<ApplicationResultEvent> event =
                ArgumentCaptor.forClass(ApplicationResultEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().status()).isEqualTo("COMPLETED");
        assertThat(event.getValue().rewardAmount()).isNull();
    }

    @Test
    void should_fail_closed_when_APPROVE_VIDEO_in_managed_beta() {
        ReflectionTestUtils.setField(companyService, "paymentsFeatureEnabled", false);
        campaign.setEscrowStatus(EscrowStatus.NONE);
        when(applicationRepository.findByIdForUpdate(10)).thenReturn(Optional.of(submittedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        CompanyApplicationActionRequest req = new CompanyApplicationActionRequest();
        req.setAction(Action.APPROVE_VIDEO);

        assertThatThrownBy(() -> companyService.manageApplication(10, 50, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        verifyNoInteractions(escrowService);
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void nonfinancial_marker_uses_old_terminal_status_and_rejects_reprocessing() {
        ReflectionTestUtils.setField(companyService, "paymentsFeatureEnabled", false);
        campaign.setEscrowStatus(EscrowStatus.NONE);
        submittedApp.setStatus(ApplicationStatus.SETTLED);
        submittedApp.setContentApprovedAt(java.time.LocalDateTime.now());
        when(applicationRepository.findByIdForUpdate(10)).thenReturn(Optional.of(submittedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        CompanyApplicationActionRequest req = new CompanyApplicationActionRequest();
        req.setAction(Action.REQUEST_CHANGES);
        req.setReviewComment("재처리 시도");

        assertThatThrownBy(() -> companyService.manageApplication(10, 50, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
        assertThat(submittedApp.getApiStatus()).isEqualTo("COMPLETED");
        assertThat(submittedApp.getRewardPaidAmount()).isNull();
        assertThat(submittedApp.getSettledAt()).isNull();
        verifyNoInteractions(escrowService);
    }

    @Test
    void should_reject_nonfinancial_completion_when_payments_enabled() {
        campaign.setEscrowStatus(EscrowStatus.FUNDED);
        when(applicationRepository.findByIdForUpdate(10)).thenReturn(Optional.of(submittedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        CompanyApplicationActionRequest req = new CompanyApplicationActionRequest();
        req.setAction(Action.APPROVE_CONTENT);

        assertThatThrownBy(() -> companyService.manageApplication(10, 50, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
        verifyNoInteractions(escrowService);
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_예외_when_SUBMITTED_아닌_상태에서_APPROVE_VIDEO() {
        // given — PENDING 상태에서는 영상 승인 불가
        submittedApp.setStatus(ApplicationStatus.PENDING);
        when(applicationRepository.findByIdForUpdate(10)).thenReturn(Optional.of(submittedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(campaign));
        CompanyApplicationActionRequest req = new CompanyApplicationActionRequest();
        req.setAction(Action.APPROVE_VIDEO);

        // when & then
        assertThatThrownBy(() -> companyService.manageApplication(10, 50, req))
                .isInstanceOf(AppException.class);
        verify(escrowService, never()).release(anyInt(), anyInt(), anyInt(), any(), anyString(), anyString());
    }
}

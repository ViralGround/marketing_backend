package com.viralground.backend.service;

import com.viralground.backend.dto.campaign.SubmitWorkRequest;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.ApplicationSubmission;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.SubmissionReviewStatus;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.ApplicationSubmissionRepository;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.MemberRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignServiceResubmitTest {

    @Mock CampaignRepository campaignRepository;
    @Mock CampaignApplicationRepository applicationRepository;
    @Mock EmailService emailService;
    @Mock MemberRepository memberRepository;
    @Mock ApplicationSubmissionRepository submissionRepository;

    @InjectMocks
    CampaignService campaignService;

    CampaignApplication approvedApp;

    @BeforeEach
    void setUp() {
        approvedApp = CampaignApplication.builder()
                .id(42)
                .campaignId(1)
                .creatorId(7)
                .status(ApplicationStatus.APPROVED)
                .resubmissionCount(0)
                .build();
    }

    @Test
    void should_ApplicationSubmission_이력_저장_when_영상_제출() {
        // given
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(null, "submissions/a.mp4", "video/mp4", 1024L);

        // when
        campaignService.submitWork(42, 7, req);

        // then
        ArgumentCaptor<ApplicationSubmission> captor = ArgumentCaptor.forClass(ApplicationSubmission.class);
        verify(submissionRepository).save(captor.capture());
        ApplicationSubmission saved = captor.getValue();
        assertThat(saved.getApplicationId()).isEqualTo(42);
        assertThat(saved.getVideoFileKey()).isEqualTo("submissions/a.mp4");
        assertThat(saved.getStatus()).isEqualTo(SubmissionReviewStatus.SUBMITTED);
    }

    @Test
    void should_resubmissionCount_증가_when_CHANGES_REQUESTED_상태에서_재제출() {
        // given
        approvedApp.setStatus(ApplicationStatus.CHANGES_REQUESTED);
        approvedApp.setResubmissionCount(1);
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(null, "submissions/v2.mp4", "video/mp4", 2048L);

        // when
        campaignService.submitWork(42, 7, req);

        // then
        ArgumentCaptor<CampaignApplication> captor = ArgumentCaptor.forClass(CampaignApplication.class);
        verify(applicationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(captor.getValue().getResubmissionCount()).isEqualTo(2);
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_예외_when_PENDING_상태에서_제출_시도() {
        // given — PENDING 은 검토 전이라 제출 불가
        approvedApp.setStatus(ApplicationStatus.PENDING);
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(null, "submissions/x.mp4", "video/mp4", 1L);

        // when & then
        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
        verify(submissionRepository, never()).save(any());
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_예외_when_SETTLED_상태에서_제출_시도() {
        // given — 정산 끝난 건은 추가 제출 불가
        approvedApp.setStatus(ApplicationStatus.SETTLED);
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(null, "submissions/x.mp4", "video/mp4", 1L);

        // when & then
        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
    }

    @Test
    void should_reviewComment_초기화_when_재제출() {
        // given
        approvedApp.setStatus(ApplicationStatus.CHANGES_REQUESTED);
        approvedApp.setReviewComment("로고 노출 부족");
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(null, "submissions/v.mp4", "video/mp4", 1L);

        // when
        campaignService.submitWork(42, 7, req);

        // then — 이전 수정요청 사유는 재제출 시 지워진다 (이력은 ApplicationSubmission 이 유지)
        ArgumentCaptor<CampaignApplication> captor = ArgumentCaptor.forClass(CampaignApplication.class);
        verify(applicationRepository).save(captor.capture());
        assertThat(captor.getValue().getReviewComment()).isNull();
    }
}

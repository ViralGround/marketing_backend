package com.viralground.backend.service;

import com.viralground.backend.dto.campaign.SubmitWorkRequest;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampaignServiceSubmitWorkTest {

    @Mock
    CampaignRepository campaignRepository;
    @Mock
    CampaignApplicationRepository applicationRepository;
    @Mock
    EmailService emailService;
    @Mock
    MemberRepository memberRepository;

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
                .build();
    }

    @Test
    void should_영상파일키_저장_SUBMITTED_상태_when_videoFileKey_제출() {
        // given
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(
                null, "submissions/abc.mp4", "video/mp4", 1024L);

        // when
        campaignService.submitWork(42, 7, req);

        // then
        ArgumentCaptor<CampaignApplication> captor = ArgumentCaptor.forClass(CampaignApplication.class);
        verify(applicationRepository).save(captor.capture());
        CampaignApplication saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(saved.getVideoFileKey()).isEqualTo("submissions/abc.mp4");
        assertThat(saved.getVideoContentType()).isEqualTo("video/mp4");
        assertThat(saved.getVideoSizeBytes()).isEqualTo(1024L);
        assertThat(saved.getSubmissionUrl()).isNull();
        assertThat(saved.getSubmittedAt()).isNotNull();
    }

    @Test
    void should_외부URL_저장_when_submissionUrl_제출_레거시() {
        // given
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(
                "https://example.com/video.mp4", null, null, null);

        // when
        campaignService.submitWork(42, 7, req);

        // then
        ArgumentCaptor<CampaignApplication> captor = ArgumentCaptor.forClass(CampaignApplication.class);
        verify(applicationRepository).save(captor.capture());
        CampaignApplication saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(saved.getSubmissionUrl()).isEqualTo("https://example.com/video.mp4");
        assertThat(saved.getVideoFileKey()).isNull();
    }

    @Test
    void should_FORBIDDEN_예외_when_다른_크리에이터가_제출() {
        // given
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(null, "submissions/x.mp4", "video/mp4", 1L);

        // when & then
        assertThatThrownBy(() -> campaignService.submitWork(42, 999, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(applicationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void should_APPLICATION_NOT_FOUND_예외_when_존재하지않는_지원ID() {
        // given
        when(applicationRepository.findById(42)).thenReturn(Optional.empty());
        SubmitWorkRequest req = new SubmitWorkRequest(null, "submissions/x.mp4", "video/mp4", 1L);

        // when & then
        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.APPLICATION_NOT_FOUND);
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_예외_when_둘다_비어있음() {
        // given
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(null, null, null, null);

        // when & then
        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
    }

    @Test
    void should_INVALID_VIDEO_FORMAT_예외_when_fileKey_있는데_contentType_누락() {
        // given
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(null, "submissions/x.mp4", null, 1L);

        // when & then
        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VIDEO_FORMAT);
    }

    @Test
    void should_VIDEO_TOO_LARGE_예외_when_fileKey_있는데_size_0이하() {
        // given
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(null, "submissions/x.mp4", "video/mp4", 0L);

        // when & then
        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VIDEO_TOO_LARGE);
    }
}

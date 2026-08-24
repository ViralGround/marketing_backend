package com.viralground.backend.service;

import com.viralground.backend.dto.campaign.SubmitWorkRequest;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import com.viralground.backend.repository.ApplicationSubmissionRepository;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.CampaignRepository;
import com.viralground.backend.repository.CreatorInstagramConnectionRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.storage.FileStorage;
import com.viralground.backend.storage.UploadOwnershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock
    ApplicationSubmissionRepository submissionRepository;
    @Mock
    FileStorage fileStorage;
    @Mock
    UploadOwnershipService uploadOwnershipService;
    @Mock
    CreatorInstagramConnectionRepository connectionRepository;
    @Spy
    Clock clock = Clock.systemDefaultZone();

    @InjectMocks
    CampaignService campaignService;

    CampaignApplication approvedApp;
    Campaign activeCampaign;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(campaignService, "uploadsFeatureEnabled", true);
        approvedApp = CampaignApplication.builder()
                .id(42)
                .campaignId(1)
                .creatorId(7)
                .status(ApplicationStatus.APPROVED)
                .build();
        // 마감 미정 캠페인 — 정상 제출 흐름이 deadline 가드를 통과하도록.
        activeCampaign = Campaign.builder()
                .id(1)
                .deadline(null)
                .build();
    }

    @Test
    void should_영상파일키_저장_SUBMITTED_상태_when_videoFileKey_제출() {
        // given
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        when(fileStorage.exists("submissions/abc.mp4")).thenReturn(true);
        when(campaignRepository.findById(1)).thenReturn(Optional.of(activeCampaign));
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
        when(campaignRepository.findById(1)).thenReturn(Optional.of(activeCampaign));
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

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "http://example.com/video",
            "https://user:password@example.com/video",
            "https://localhost/video",
            "https://127.0.0.1/video",
            "https://10.0.0.8/video",
            "https://example.com/video#private-fragment",
            "   ",
            " https://example.com/video",
            "https://example.com/video\nInjected: value"
    })
    void should_INVALID_CAMPAIGN_INPUT_when_submissionUrl_공개HTTPS_정책_위반(String unsafeUrl) {
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(unsafeUrl, null, null, null);

        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
        verify(applicationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_when_submissionUrl_500자_초과() {
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(
                "https://example.com/" + "a".repeat(481), null, null, null);

        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
        verify(applicationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_when_파일과_위험URL을_함께_제출() {
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        SubmitWorkRequest req = new SubmitWorkRequest(
                "https://localhost/internal", "submissions/abc.mp4", "video/mp4", 1024L);

        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
        verify(fileStorage, org.mockito.Mockito.never()).exists(anyString());
        verify(applicationRepository, org.mockito.Mockito.never()).save(any());
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

    @Test
    void should_CAMPAIGN_CLOSED_예외_when_마감_지난_캠페인에_제출() {
        // given — 입력 검증을 통과한 정상 요청도 마감 후에는 거부
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        when(fileStorage.exists("submissions/x.mp4")).thenReturn(true);
        Campaign expired = Campaign.builder()
                .id(1)
                .deadline(LocalDateTime.now().minusDays(1))
                .build();
        when(campaignRepository.findById(1)).thenReturn(Optional.of(expired));
        SubmitWorkRequest req = new SubmitWorkRequest(null, "submissions/x.mp4", "video/mp4", 1024L);

        // when & then
        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CAMPAIGN_CLOSED);
        verify(applicationRepository, org.mockito.Mockito.never()).save(any());
        verify(submissionRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void should_SUBMISSION_NOT_FOUND_예외_when_videoFileKey_파일이_존재하지_않음() {
        // given — 클라이언트가 presign 만 받고 실제 업로드 없이 key 를 제출하거나 임의 문자열을 보낸 케이스
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        when(fileStorage.exists(anyString())).thenReturn(false);
        SubmitWorkRequest req = new SubmitWorkRequest(
                null, "submissions/does-not-exist.mp4", "video/mp4", 1024L);

        // when & then
        assertThatThrownBy(() -> campaignService.submitWork(42, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBMISSION_NOT_FOUND);
        verify(applicationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void should_trackingMode_AUTO_when_크리에이터가_인스타_연동됨() {
        // given — creator 7 인스타 연동(CONNECTED)
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(activeCampaign));
        when(connectionRepository.findByCreatorId(7)).thenReturn(Optional.of(
                CreatorInstagramConnection.builder().creatorId(7).status(ConnectionStatus.CONNECTED).build()));
        SubmitWorkRequest req = new SubmitWorkRequest("https://example.com/v.mp4", null, null, null);

        // when
        String trackingMode = campaignService.submitWork(42, 7, req);

        // then — 연결됨이면 자동 추적
        assertThat(trackingMode).isEqualTo("AUTO");
    }

    @Test
    void should_trackingMode_MANUAL_when_크리에이터_미연동() {
        // given — 연동 레코드 없음
        when(applicationRepository.findById(42)).thenReturn(Optional.of(approvedApp));
        when(campaignRepository.findById(1)).thenReturn(Optional.of(activeCampaign));
        when(connectionRepository.findByCreatorId(7)).thenReturn(Optional.empty());
        SubmitWorkRequest req = new SubmitWorkRequest("https://example.com/v.mp4", null, null, null);

        // when
        String trackingMode = campaignService.submitWork(42, 7, req);

        // then — 미연결이면 수동 추적(제출은 막지 않음)
        assertThat(trackingMode).isEqualTo("MANUAL");
    }
}

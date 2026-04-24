package com.viralground.backend.service;

import com.viralground.backend.dto.metric.UpsertMetricRequest;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.SubmissionMetric;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.SubmissionMetricRepository;
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
class MetricsServiceTest {

    @Mock SubmissionMetricRepository metricRepository;
    @Mock CampaignApplicationRepository applicationRepository;

    @InjectMocks
    MetricsService metricsService;

    CampaignApplication settledApp;

    @BeforeEach
    void setUp() {
        settledApp = CampaignApplication.builder()
                .id(10).creatorId(7)
                .status(ApplicationStatus.SETTLED)
                .build();
    }

    @Test
    void should_신규_metric_저장_when_처음_입력() {
        // given
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        when(metricRepository.findByApplicationId(10)).thenReturn(Optional.empty());
        UpsertMetricRequest req = new UpsertMetricRequest();
        req.setViews(10_000L);
        req.setLikes(300L);
        req.setComments(20L);
        req.setExternalUrl("https://instagram.com/p/abc");

        // when
        metricsService.upsert(10, 7, req);

        // then
        ArgumentCaptor<SubmissionMetric> captor = ArgumentCaptor.forClass(SubmissionMetric.class);
        verify(metricRepository).save(captor.capture());
        SubmissionMetric saved = captor.getValue();
        assertThat(saved.getApplicationId()).isEqualTo(10);
        assertThat(saved.getViews()).isEqualTo(10_000L);
        assertThat(saved.getExternalUrl()).isEqualTo("https://instagram.com/p/abc");
    }

    @Test
    void should_기존_metric_업데이트_when_이미_존재() {
        // given
        SubmissionMetric existing = SubmissionMetric.builder()
                .id(99).applicationId(10).views(100L).likes(10L).comments(1L).build();
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        when(metricRepository.findByApplicationId(10)).thenReturn(Optional.of(existing));
        UpsertMetricRequest req = new UpsertMetricRequest();
        req.setViews(20_000L);
        req.setLikes(500L);
        req.setComments(50L);

        // when
        metricsService.upsert(10, 7, req);

        // then
        ArgumentCaptor<SubmissionMetric> captor = ArgumentCaptor.forClass(SubmissionMetric.class);
        verify(metricRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(99);
        assertThat(captor.getValue().getViews()).isEqualTo(20_000L);
    }

    @Test
    void should_FORBIDDEN_when_본인_application_아님() {
        // given
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        UpsertMetricRequest req = new UpsertMetricRequest();
        req.setViews(1L); req.setLikes(0L); req.setComments(0L);

        // when & then
        assertThatThrownBy(() -> metricsService.upsert(10, 999, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void should_METRIC_FORBIDDEN_when_SETTLED_아님() {
        // given
        settledApp.setStatus(ApplicationStatus.SUBMITTED);
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        UpsertMetricRequest req = new UpsertMetricRequest();
        req.setViews(1L); req.setLikes(0L); req.setComments(0L);

        // when & then
        assertThatThrownBy(() -> metricsService.upsert(10, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.METRIC_FORBIDDEN);
    }

    @Test
    void should_INVALID_CAMPAIGN_INPUT_when_악성_URL() {
        // given
        when(applicationRepository.findById(10)).thenReturn(Optional.of(settledApp));
        UpsertMetricRequest req = new UpsertMetricRequest();
        req.setViews(1L); req.setLikes(0L); req.setComments(0L);
        req.setExternalUrl("javascript:alert('xss')");

        // when & then
        assertThatThrownBy(() -> metricsService.upsert(10, 7, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CAMPAIGN_INPUT);
    }
}

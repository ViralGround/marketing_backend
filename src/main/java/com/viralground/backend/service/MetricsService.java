package com.viralground.backend.service;

import com.viralground.backend.dto.metric.UpsertMetricRequest;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.SubmissionMetric;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.SubmissionMetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MetricsService {

    private final SubmissionMetricRepository metricRepository;
    private final CampaignApplicationRepository applicationRepository;

    /**
     * 본인이 지원한 SETTLED application 에만 성과를 upsert.
     * 외부 SNS 게시물 URL 은 선택값이며 http/https 만 허용.
     */
    @Transactional
    public SubmissionMetric upsert(Integer applicationId, Integer creatorId, UpsertMetricRequest req) {
        CampaignApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));
        if (!app.getCreatorId().equals(creatorId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        if (app.getStatus() != ApplicationStatus.SETTLED) {
            throw new AppException(ErrorCode.METRIC_FORBIDDEN);
        }
        if (req.getExternalUrl() != null && !req.getExternalUrl().isBlank()
                && !isSafeHttpUrl(req.getExternalUrl())) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }

        SubmissionMetric metric = metricRepository.findByApplicationId(applicationId)
                .orElseGet(() -> SubmissionMetric.builder().applicationId(applicationId).build());
        metric.setViews(req.getViews());
        metric.setLikes(req.getLikes());
        metric.setComments(req.getComments());
        metric.setExternalUrl(req.getExternalUrl());
        metric.setRecordedAt(LocalDateTime.now());
        return metricRepository.save(metric);
    }

    private static boolean isSafeHttpUrl(String raw) {
        try {
            java.net.URI u = java.net.URI.create(raw);
            String scheme = u.getScheme();
            return "http".equals(scheme) || "https".equals(scheme);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

package com.viralground.backend.service;

import com.viralground.backend.dto.metric.UpsertMetricRequest;
import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.CampaignApplication;
import com.viralground.backend.entity.SubmissionMetric;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.CampaignApplicationRepository;
import com.viralground.backend.repository.SubmissionMetricRepository;
import com.viralground.backend.validation.PublicUrlPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MetricsService {

    private final SubmissionMetricRepository metricRepository;
    private final CampaignApplicationRepository applicationRepository;

    /**
     * 본인이 완료한 COMPLETED/SETTLED application 에만 성과를 upsert.
     * 외부 SNS 게시물 URL 은 선택값이며 공개 absolute HTTPS 주소만 허용.
     */
    @Transactional
    public SubmissionMetric upsert(Integer applicationId, Integer creatorId, UpsertMetricRequest req) {
        CampaignApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));
        if (!app.getCreatorId().equals(creatorId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        if (!app.isCompletedWork()) {
            throw new AppException(ErrorCode.METRIC_FORBIDDEN);
        }
        String normalizedExternalUrl;
        try {
            normalizedExternalUrl = PublicUrlPolicy.normalizeOptional(req.getExternalUrl());
        } catch (AppException invalidPublicUrl) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }

        try {
            return saveMetric(applicationId, req, normalizedExternalUrl);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 row 를 만든 경우: 재시도 시에는 find 가 기존 row 를 반환하므로
            // update 경로로 들어간다. 두 번째 시도에서도 실패하면 상위로 전파.
            return saveMetric(applicationId, req, normalizedExternalUrl);
        }
    }

    private SubmissionMetric saveMetric(Integer applicationId, UpsertMetricRequest req,
                                        String normalizedExternalUrl) {
        SubmissionMetric metric = metricRepository.findByApplicationId(applicationId)
                .orElseGet(() -> SubmissionMetric.builder().applicationId(applicationId).build());
        metric.setViews(req.getViews());
        metric.setLikes(req.getLikes());
        metric.setComments(req.getComments());
        metric.setExternalUrl(normalizedExternalUrl);
        metric.setRecordedAt(LocalDateTime.now());
        return metricRepository.saveAndFlush(metric);
    }

}

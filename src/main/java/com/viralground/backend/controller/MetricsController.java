package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.metric.UpsertMetricRequest;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.service.MetricsService;
import com.viralground.backend.service.PerformanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;
    private final PerformanceService performanceService;

    @PutMapping("/me/applications/{id}/metrics")
    public ResponseEntity<Map<String, Object>> upsert(
            @PathVariable Integer id,
            @Valid @RequestBody UpsertMetricRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null || authUser.getRole() != Role.CREATOR) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        var saved = metricsService.upsert(id, authUser.getId(), req);
        return ResponseEntity.ok(Map.of("id", saved.getId(), "message", "성과가 저장되었습니다."));
    }

    @GetMapping("/me/performance")
    public ResponseEntity<Map<String, Object>> myPerformance(@AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null) throw new AppException(ErrorCode.FORBIDDEN);
        return ResponseEntity.ok(performanceService.getCreatorPerformance(authUser.getId()));
    }

    @GetMapping("/company/campaigns/{id}/performance")
    public ResponseEntity<Map<String, Object>> campaignPerformance(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null) throw new AppException(ErrorCode.FORBIDDEN);
        return ResponseEntity.ok(
                performanceService.getCampaignPerformance(id, authUser.getId(), authUser.getRole()));
    }
}

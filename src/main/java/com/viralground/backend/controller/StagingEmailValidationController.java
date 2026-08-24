package com.viralground.backend.controller;

import com.viralground.backend.dto.admin.StagingEmailValidationProbeRequest;
import com.viralground.backend.service.StagingEmailValidationProbeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ADMIN authorization is inherited from /admin/** and this path is deliberately
 * absent from SecurityConfig's CSRF ignore list.
 */
@RestController
@RequestMapping("/admin/email-validation/probes")
@RequiredArgsConstructor
public class StagingEmailValidationController {

    private final StagingEmailValidationProbeService probeService;

    @PostMapping
    ResponseEntity<Map<String, String>> queue(
            @Valid @RequestBody StagingEmailValidationProbeRequest request) {
        probeService.queue(request.template());
        return ResponseEntity.accepted().body(Map.of("status", "QUEUED"));
    }
}
